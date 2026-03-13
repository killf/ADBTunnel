package com.adbtunnel.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adbtunnel.databinding.ActivityConfigBinding;
import com.adbtunnel.util.PrefsHelper;

public class ConfigActivity extends AppCompatActivity {

    private ActivityConfigBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfigBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.etServerUrl.setText(PrefsHelper.getServerUrl(this));

        binding.btnSave.setOnClickListener(v -> save());
        binding.btnCancel.setOnClickListener(v -> finish());
    }

    private void save() {
        String url = binding.etServerUrl.getText().toString().trim();

        if (TextUtils.isEmpty(url)) {
            binding.etServerUrl.setError("服务器地址不能为空");
            return;
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            binding.etServerUrl.setError("地址应以 ws:// 或 wss:// 开头");
            return;
        }

        PrefsHelper.setServerUrl(this, url);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
