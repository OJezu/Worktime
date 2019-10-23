/* Copyright 2018 Mateusz Findeisen */

package com.example.mfind.timetracker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

// Since to SDK 26
// import java.time.LocalDateTime;

// Backported java.time.* to SDK 25
import org.threeten.bp.LocalDateTime;

import static com.example.mfind.timetracker.MainActivity.changeSecondsToFormat;
import static java.lang.Thread.sleep;

public class NetworkStateCheck extends Service {
    private static final String TAG = "NetworkStateCheck";

    private String wifiSSIDRegexp = "";
    private int maxBreakTime = 0;
    private String currentNetworkSSID = "";
    private Boolean currentWifiIsCorrect = false;
    private int serviceDowntime = 0;

    private static final String CHANNEL_ID = "Worktime notification - service is working";
    private long connectionCurrentTime;
    private long connectionStartTime;

    private LocalDateTime startTime = null;

    private Context context;

    FileManipulationsPersistentData fmpd;
    FileManipulationsApplicationInfo fmai;

    IBinder mBinder = new LocalBinder();

    /**
     * when someone is binding to this class, we return out binder - our ~interface~ to talk with us
     * @param intent - specified intent of the class that wants to bind to us, we could read this
     *               intent and decide based on that if we actually want to give caller a binder
     *               or not
     * @return - returns a binder
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Returns instance of out class when created
     */
    class LocalBinder extends Binder {
        NetworkStateCheck getServerInstance() {
            return NetworkStateCheck.this;
        }
    }

    /**
     * @return - returns currently monitored wifi SSID regex
     */
    public String getCurrentSSID(){
        return wifiSSIDRegexp;
    }

    /**
     * @return - returns number of seconds not yet saved to application data
     */
    long getNotSavedTime(){
        if(currentWifiIsCorrect)
            return (SystemClock.elapsedRealtime() - connectionCurrentTime)/1000;
        return 0;
    }

    String getStartTime(){
        return "Service start time: "+startTime;
    }
    long getLastUpdateDifference(){
        return (SystemClock.elapsedRealtime() - connectionCurrentTime)/1000;
    }
    long getLastSavedValue(){
        return saveData(0);
    }
    String getHowLongAgoWeConnectedToWifi(){
        if(currentWifiIsCorrect)
            return changeSecondsToFormat((SystemClock.elapsedRealtime() - connectionStartTime)/1000);
        else
            return "-NOT-STARTED-";
    }

    /**
     * Sets new wifi SSID regex, saving data to proto if needed and afterwards
     * @param ssid - new SSID regex
     */
    public void setWifiSSIDRegexp(String ssid){
        wifiSSIDRegexp = ssid;
        currentWifiIsCorrect = wifiSSIDRegexp.equals(currentNetworkSSID);
        saveYourData();
        doBindInfo();
        fmai.setSSID(ssid);
        doUnbindInfo();
    }

    /**
     * saves current ticker if possible
     */
    public void saveYourData(){
        Log.i(TAG, "### saveYourData: Attempt to save!");

        doBindInfo();
        fmai.setLastSaveTime((int)(SystemClock.elapsedRealtime()/1000));
        doUnbindInfo();

        int seconds;
        if(currentWifiIsCorrect){
            int temp;
            temp = (int)(SystemClock.elapsedRealtime() - connectionCurrentTime)/1000;
            connectionCurrentTime = SystemClock.elapsedRealtime();
            Log.i(TAG, "### saveYourData: Saving data! " + temp + "s");
            seconds = saveData(temp);
        }else
            seconds = saveData(0);
        Log.i(TAG, "### saveYourData: Today saved time: " + MainActivity.changeSecondsToFormat(seconds));
    }

    /**
     * Used to actually save data
     * @param seconds - number of seconds to prepend
     * @return - returns today ticker
     */
    private int saveData(int seconds){
        // gets todays ticker, increases it and returns current ticker
        doBindData();
        int temp = fmpd.prependTicker(seconds);
        //fmpd.invalidateInitialization();
        doUnbindData();
        return temp;
    }

    /**
     * Start FileManipulator Service (we cannot start more than one instance, we actually
     * get already initialized instance when doing new() and startService()
     */
    private void doBindData(){
        fmpd = new FileManipulationsPersistentData();
        final Intent serviceF = new Intent(context, FileManipulationsPersistentData.class);
        startService(serviceF);
        fmpd.setContext(context);
    }

    /**
     * This stops the service.. unless someone is already binded to it (for example MainActivity)
     */
    private void doUnbindData(){
        fmpd.stopSelf();
    }

    /**
     * Start ApplicationInfo Service (we cannot start more than one instance, we actually
     * get already initialized instance when doing new() and startService()
     */
    private void doBindInfo(){
        fmai = new FileManipulationsApplicationInfo();
        final Intent serviceF = new Intent(context, FileManipulationsApplicationInfo.class);
        startService(serviceF);
        fmai.setContext(context);
    }

    /**
     * This stops the service.. unless someone is already binded to it
     */
    private void doUnbindInfo(){
        fmai.stopSelf();
    }

    /**
     * Invoked when we get created
     *
     * This also registers this service as Foreground Service (and also creates
     * permanent notification required for this)
     *
     * We also register BroadcastReceiver
     */
    @Override
    public void onCreate(){
        super.onCreate();
        this.context = getApplicationContext();

        startTime = LocalDateTime.now();

        connectionCurrentTime = 0;
        connectionStartTime = 0;
        currentWifiIsCorrect = false;
        prepareAndStartForeground();
        readAppInfo();
        registerWifiChangeReceiver();
    }

    /**
     * @return - returns downtime for debugging purposes
     */
    public String getDowntime(){
        if(serviceDowntime > 0)
            return changeSecondsToFormat(serviceDowntime);
        else
            return "-PHONE-RESTARTED-";
    }

    /**
     * Reads already saved wifi SSID regex and currently saved max break time as saved in
     * application memory
     */
    private void readAppInfo(){
        doBindInfo();
        wifiSSIDRegexp = fmai.getSSID();
        maxBreakTime = fmai.getMaxBreakTime();
        int lastSave = fmai.getLastSaveTime();
        serviceDowntime = lastSave == 0 ? 0 : (int)(SystemClock.elapsedRealtime()/1000 - lastSave);
        serviceDowntime = serviceDowntime <= 0 ? 0 : serviceDowntime;
        doUnbindInfo();
    }

    /**
     * Prepares permanent notification, and registers this service as foreground with
     * created notification
     */
    private void prepareAndStartForeground(){
        try {
            Intent notificationIntent = new Intent(context, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
                    notificationIntent, 0);
            String channelID = "This_is_my_channel_ha!";
            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, channelID);
            notification.setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Worktime WiFi tracking")
                    .setContentText("Watching your WiFi status to detect location")
                    .setShowWhen(false)
                    .setContentIntent(pendingIntent);
            createNotificationChannel();
            startForeground(1337, notification.build());
            Log.i(TAG, "### prepareAndStartForeground: Foreground service started!");
        }catch(SecurityException e){
            Log.e(TAG, "### ### ### prepareAndStartForeground: That... did not happen before...");
            e.printStackTrace();
        }
    }

    /**
     * Creates notification channel for permanent notification
     */
    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String description = "Worktime tracker notification channel";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel("This_is_my_channel_ha!", CHANNEL_ID, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Invoked when system for some reason kills our application, should probably never happen
     * unless this service doesn't run in foreground
     */
    @Override
    public void onDestroy(){
        saveYourData();
        super.onDestroy();
        Log.d(TAG, "### ### ### onDestroy: because we run in foreground, we should not ever be destroyed, unless in critical memory condition or when shutting down phone?");
        //to create a crash report...
        // Log.e(TAG, "### onDestroy: " + 1/0);

        //unregisterReceiver(mWifiStateChangeReceiver);
        //mWifiStateChangeReceiver = null;
    }

    /**
     * Invoked when system is low on memory, we might not be able to save data in a second
     * so we save it now if necessary
     */
    @Override
    public void onLowMemory(){
        Log.i(TAG, "### onLowMemory: Syncing!!!");

        saveYourData();

        super.onLowMemory();
    }

    String FetchNetworkSSID(){
        while(true){
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return "<null>";
            }
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null) {
                return "<null>";
            }
            String ssid = wifiInfo.getSSID();
            ssid = ssid.replaceAll("^\"|\"$", ""); // ?
            // We get <unknown ssid> if we lack permission, or WiFi is still initializing.
            if (!ssid.equals("<unknown ssid>")) {
                return ssid;
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return "<denied>";
            }
            // We need to sleep and retry here, to be able to handle WiFi notifications.
            try {
                sleep(500);
            } catch (InterruptedException e) {
                return "<interrupted>";
            }
        }
    }

    /**
     * Starts, stops or ignores wifi change and starts or stops counting if necessary
     * @param type - Boolean - true if currently connected to wifi, false if otherwise
     */
    private void startOrStopCounting(Boolean type){
        if(type){
            if(currentWifiIsCorrect){
                Log.i(TAG, "### startOrStopCounting: repeated signal detected");
                return;
            }
            currentNetworkSSID = FetchNetworkSSID();
            Log.d(TAG, "### startOrStopCounting: Currently connected to " + currentNetworkSSID);

            currentWifiIsCorrect = currentNetworkSSID.matches(wifiSSIDRegexp);
            if(currentWifiIsCorrect) {
                if(!detectShortBreak()) {
                    Log.i(TAG, "### startOrStopCounting: wifi connected");
                    connectionStartTime = SystemClock.elapsedRealtime();
                }
            }

            connectionCurrentTime = SystemClock.elapsedRealtime();
        }else {
            if (currentWifiIsCorrect) { /// if not first run, when app started with WiFi turned off
                saveYourData();
                currentWifiIsCorrect = false;
                connectionCurrentTime = SystemClock.elapsedRealtime();
                Log.i(TAG, "### startOrStopCounting: Wifi disconnected : stopped ticker!");
            }
        }
    }

    /**
     * Detects if wifi connection break was shorter than specified in application info proto
     * @return - returns true if break was 'short', false otherwise
     */
    private Boolean detectShortBreak(){
        if(SystemClock.elapsedRealtime() - connectionCurrentTime <= maxBreakTime * 1000){
            Log.i(TAG, "### detectShortBreak: Saving...");
            saveYourData();
            return true;
        }
        return false;
    }

    /**
     * registers receiver to receive NETWORK_STATE_CHANGED_ACTION broadcast signal from system
     */
    private void registerWifiChangeReceiver()
    {
        // broadcast Receiver - used to detect network change
        BroadcastReceiver mWifiStateChangeReceiver = new BroadcastReceiver() {
            /**
             * Invoked when someone broadcasted signal we are registered for
             * @param context - application that send the broadcast
             * @param intent - intent associated with the broadcast
             */
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                    // Do your work.
                    NetworkInfo nwInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    startOrStopCounting(nwInfo.isConnected());
                } else {
                    Log.d(TAG, "### ### ### onReceive: HOW COULD THAT HAPPEN??? That's the only action I'm looking for!");
                }
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        this.registerReceiver(mWifiStateChangeReceiver, filter);
    }
}
