/*
 * Copyright (c) 2018 FTC team 4634 FROGbots
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.frogbots.ftcopmodetuner.network;

import android.app.Application;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.widget.Toast;

import net.frogbots.ftcopmodetuner.R;
import net.frogbots.ftcopmodetuner.misc.EntireAppLifecycleListener;
import net.frogbots.ftcopmodetuner.prefs.GlobalPrefs;
import net.frogbots.ftcopmodetuner.prefs.PrefKeys;
import net.frogbots.ftcopmodetuner.misc.SimpleSoundPool;
import net.frogbots.ftcopmodetunercommon.misc.DataConstants;
import net.frogbots.ftcopmodetunercommon.networking.udp.ConnectionStatus;

import java.util.ArrayList;

public class NetworkedApplication extends Application implements LifecycleObserver, SharedPreferences.OnSharedPreferenceChangeListener, NetworkEventsListener
{
    private GlobalPrefs globalPrefs;
    private int port;
    private String addr;
    private int serverHeartbeatInterval;
    private int noResponseFromServerTimeoutMs;
    private BroadcastReceiver networkBroadcastReceiver;
    private NetworkingManager networkingManager;
    private Handler handler;
    private Toast toast;
    private long timeOfLastNetworkEvent;
    private boolean connectionSounds;
    private SimpleSoundPool soundPool;
    private int DISCONNECT_SOUND_ID;
    private int CONNECT_SOUND_ID;
    private ArrayList<EntireAppLifecycleListener> entireAppLifecycleListeners = new ArrayList<>();

    @Override
    public void onCreate()
    {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        GlobalPrefs.initInstance(getApplicationContext());
        globalPrefs = GlobalPrefs.getInstance();
        globalPrefs.registerOnSharedPreferenceChangeListener(this);
        networkingManager = NetworkingManager.getInstance();
        networkingManager.registerListener(this);
        handler = new Handler(getMainLooper());

        networkBroadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                handleWifiRadioConnectedState();
            }
        };

        loadSoundpoolThings();
        updateThings();
    }

    public void addEntireAppLifecycleListener(EntireAppLifecycleListener listener)
    {
        entireAppLifecycleListeners.add(listener);
    }

    public void removeEntireAppLifecycleListener(EntireAppLifecycleListener listener)
    {
        entireAppLifecycleListeners.remove(listener);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private void resume()
    {
        System.out.println("ON_RESUME");
        updateThings();
        handleWifiRadioConnectedState();
        registerReceiver(networkBroadcastReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        for(EntireAppLifecycleListener listener : entireAppLifecycleListeners)
        {
            listener.onEntireAppResume();
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void pause()
    {
        System.out.println("ON_PAUSE");
        networkingManager.stop();
        unregisterReceiver(networkBroadcastReceiver);

        for(EntireAppLifecycleListener listener : entireAppLifecycleListeners)
        {
            listener.onEntireAppPause();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        updateThings();

        /*
         * If we're not connected to a network, then we don't want to load the networking
         * manager even though the parameters have changed. They'll get loaded when we
         * connect to a network again, anyway.
         */
        if(isWifiRadioConnectedToANetwork())
        {
            networkingManager.reloadIfNecessary(addr, port, serverHeartbeatInterval, noResponseFromServerTimeoutMs);
        }
    }

    @Override
    public void onNetworkStateUpdateFromBackgroundThread(final ConnectionStatus status, final long eventTime)
    {
        handler.post(new Runnable()
        {
            @Override
            public void run()
            {
                /*
                 * Just to make sure that thread queueing doesn't
                 * screw up the order....
                 */
                if (eventTime > timeOfLastNetworkEvent)
                {
                    timeOfLastNetworkEvent = eventTime;

                    if (toast != null)
                    {
                        toast.cancel();
                    }

                    toast = Toast.makeText(getApplicationContext(), "Network: " + status.toString(), Toast.LENGTH_SHORT);
                    toast.show();
                }
            }
        });

        if(connectionSounds)
        {
            if(status == ConnectionStatus.CONNECTED)
            {
                soundPool.play(CONNECT_SOUND_ID);
            }

            else if(status == ConnectionStatus.NOT_CONNECTED)
            {
                soundPool.play(DISCONNECT_SOUND_ID);
            }
        }
    }

    private void handleWifiRadioConnectedState()
    {
        if (isWifiRadioConnectedToANetwork())
        {
            networkingManager.begin(addr, port, serverHeartbeatInterval, noResponseFromServerTimeoutMs);
        } else
        {
            networkingManager.stop();
            networkingManager.setConnectionStatusAndNotify(ConnectionStatus.WIFI_RADIO_OFF_OR_NOT_CONNECTED_TO_INFRASTRUCTURE_NETWORK);
        }
    }

    private boolean isWifiRadioConnectedToANetwork()
    {
        return ((ConnectivityManager) (getSystemService(Context.CONNECTIVITY_SERVICE))).getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
    }

    private void updateThings()
    {
        connectionSounds = globalPrefs.getBoolean(PrefKeys.CONNECTION_SOUNDS, true);
        serverHeartbeatInterval = Integer.parseInt(globalPrefs.getString(PrefKeys.HEARTBEAT_INTERVAL, String.valueOf(DataConstants.DEFAULT_HEARTBEAT_INTERVAL_MS)));
        noResponseFromServerTimeoutMs = Integer.parseInt(globalPrefs.getString(PrefKeys.NO_SERVER_RESPONSE_TIMEOUT, String.valueOf(DataConstants.DEFAULT_CONNECTION_TIMEOUT_MS)));
        addr = globalPrefs.getString(PrefKeys.IP_ADDR, DataConstants.DEFAULT_IP_ADDR);
        port = Integer.parseInt(globalPrefs.getString(PrefKeys.PORT, String.valueOf(DataConstants.DEFAULT_PORT)));
    }

    private void loadSoundpoolThings()
    {
        soundPool = new SimpleSoundPool(getApplicationContext());
        CONNECT_SOUND_ID = soundPool.load(R.raw.connect);
        DISCONNECT_SOUND_ID = soundPool.load(R.raw.disconnect);

        /*
         * Wait to allow the sound pool to load the sounds.
         * Yes, blocking the UI thread is dumb, but the
         * OnLoadCompleteListener was not working soo.....
         */
        try
        {
            Thread.sleep(250);
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    public int getPort()
    {
        return port;
    }

    public String getAddr()
    {
        return addr;
    }
}
