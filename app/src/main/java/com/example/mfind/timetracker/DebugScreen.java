package com.example.mfind.timetracker;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;


import static com.example.mfind.timetracker.MainActivity.changeSecondsToFormat;

public class DebugScreen extends AppCompatActivity {

    boolean mBoundedReceiver;
    NetworkStateCheck mServerReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_screen);
        mBoundedReceiver = false;
    }

    @Override
    protected void onStart(){
        super.onStart();
        Intent mIntentSR = new Intent(this, NetworkStateCheck.class);
        bindService(mIntentSR, mConnectionToReceiver, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(mConnectionToReceiver);
    }

    ServiceConnection mConnectionToReceiver = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NetworkStateCheck.LocalBinder mLocalBinder = (NetworkStateCheck.LocalBinder)service;
            mServerReceiver = mLocalBinder.getServerInstance();
            mBoundedReceiver = true;

            TextView t;
            t = findViewById(R.id.serviceStartTime);
            t.setText(mServerReceiver.getStartTime());

            t = findViewById(R.id.timeAgo);
            t.setText("Updated " + changeSecondsToFormat(mServerReceiver.getLastUpdateDifference()) + " ago");

            t = findViewById(R.id.lastValue);
            t.setText("Last value was: " + changeSecondsToFormat(mServerReceiver.getLastSavedValue()));

            t = findViewById(R.id.connectedToWifiFor);
            t.setText("We are connected to wifi for " + mServerReceiver.getHowLongAgoWeConnectedToWifi());

            t = findViewById(R.id.serviceDowntime);
            t.setText("Approx. downtime: " + mServerReceiver.getDowntime());

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBoundedReceiver = false;
            mServerReceiver = null;
        }
    };
}
