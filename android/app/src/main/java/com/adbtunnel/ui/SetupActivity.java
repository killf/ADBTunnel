package com.adbtunnel.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.adbtunnel.databinding.ActivitySetupBinding;
import com.adbtunnel.device.AdbStateChecker;

public class SetupActivity extends AppCompatActivity {

    private ActivitySetupBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnGoToSettings.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));

        binding.btnConfigure.setOnClickListener(v ->
            startActivity(new Intent(this, ConfigActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndProceed();
    }

    private void checkAndProceed() {
        AdbStateChecker.AdbStatus status = AdbStateChecker.check(this);

        if (!status.developerModeEnabled) {
            showState("未开启开发者模式",
                "请前往「设置 → 关于手机」，连续点击「版本号」7次开启开发者模式。",
                true, false);
            return;
        }

        if (!status.adbEnabled) {
            showState("未开启 USB 调试",
                "请前往「设置 → 开发者选项」，开启「USB 调试」。",
                true, false);
            return;
        }

        // ADB 已就绪，直接跳转主界面
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void showState(String title, String desc, boolean showSettings, boolean showConfig) {
        binding.tvTitle.setText(title);
        binding.tvDesc.setText(desc);
        binding.btnGoToSettings.setVisibility(showSettings
            ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.btnConfigure.setVisibility(showConfig
            ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
