package com.adbtunnel.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.adbtunnel.databinding.ActivityMainBinding;
import com.adbtunnel.device.DeviceRegistry;
import com.adbtunnel.service.TunnelForegroundService;
import com.adbtunnel.util.PrefsHelper;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private boolean serviceRunning = false;

    private final BroadcastReceiver tunnelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(TunnelForegroundService.EXTRA_STATUS);
            if ("connected".equals(status)) {
                updateStatus("已连接", true);
            } else if ("disconnected".equals(status)) {
                updateStatus("重连中…", true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String deviceId = DeviceRegistry.getOrCreateDeviceId(this);
        binding.tvDeviceId.setText(deviceId);
        binding.tvServerUrl.setText(PrefsHelper.getServerUrl(this));

        binding.btnToggle.setOnClickListener(v -> toggleService());
        binding.btnConfig.setOnClickListener(v ->
            startActivity(new Intent(this, ConfigActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("com.adbtunnel.TUNNEL_EVENT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tunnelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tunnelReceiver, filter);
        }
        binding.tvServerUrl.setText(PrefsHelper.getServerUrl(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tunnelReceiver); } catch (Exception ignored) {}
    }

    private void toggleService() {
        Intent intent = new Intent(this, TunnelForegroundService.class);
        if (!serviceRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceRunning = true;
            updateStatus("连接中…", true);
        } else {
            intent.setAction(TunnelForegroundService.ACTION_STOP);
            startService(intent);
            serviceRunning = false;
            updateStatus("已停止", false);
        }
    }

    private void updateStatus(String status, boolean running) {
        binding.tvStatus.setText(status);
        binding.btnToggle.setText(running ? "停止" : "启动");
        serviceRunning = running;
    }
}
