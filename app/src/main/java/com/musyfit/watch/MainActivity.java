package com.musyfit.watch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 77;
    private static final int REQ_BT = 101;
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private ValueCallback<Uri[]> chooser;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice currentDevice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> devices = new HashMap<>();
    private final Set<String> emitted = new HashSet<>();
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean retried133 = false;
    private String currentName = "";
    private String currentAddress = "";

    private final Runnable connectTimeout = () -> {
        if (connecting && !connected) {
            js("onBtConnectionFailed", "Tempo esgotado. Reinicie o Bluetooth do relógio e tente novamente.");
            safeCloseGatt();
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || !isT800(safeName(d))) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (state == BluetoothDevice.BOND_BONDED) js("onPairState", "Pareamento Android concluído.", "bonded");
            else if (state == BluetoothDevice.BOND_BONDING) js("onPairState", "Confirme o pareamento no relógio/celular.", "bonding");
            else js("onPairState", "Relógio não está pareado no Android.", "none");
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            registerReceiverCompat();
            BluetoothManager bm = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            btAdapter = bm != null ? bm.getAdapter() : null;

            webView = new WebView(this);
            setContentView(webView);
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            webView.setBackgroundColor(Color.rgb(2,8,17));
            webView.setWebViewClient(new WebViewClient());
            webView.addJavascriptInterface(new Bridge(), "MusyFitBridge");
            webView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                    if (chooser != null) chooser.onReceiveValue(null);
                    chooser = cb;
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("image/*");
                        startActivityForResult(i, REQ_FILE);
                        return true;
                    } catch (Throwable e) {
                        chooser = null;
                        return false;
                    }
                }
            });
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Throwable t) {
            showFatal("MusyFit Watch não conseguiu iniciar.\n\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private void registerReceiverCompat() {
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(bondReceiver, f);
    }

    private void showFatal(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.rgb(5,15,28));
        tv.setTextSize(18f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(36,36,36,36);
        setContentView(tv);
    }

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT);
        }
    }

    private void js(String function, String... args) {
        if (webView == null || function == null) return;
        StringBuilder sb = new StringBuilder("javascript:").append(function).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            String v = args[i] == null ? "" : args[i];
            sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")).append('"');
        }
        sb.append(')');
        runOnUiThread(() -> {
            try { if (!isFinishing() && webView != null) webView.evaluateJavascript(sb.toString(), null); }
            catch (Throwable ignored) {}
        });
    }

    private String safeName(BluetoothDevice d) {
        try {
            if (d != null && hasBtPermissions()) {
                String n = d.getName();
                return n == null ? "" : n;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private boolean isT800(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replace(" ", "");
        return n.contains("t800ultra2") || n.contains("t800ultra") || n.equals("t800") || n.contains("ultra2");
    }

    private void emitDevice(BluetoothDevice d, int rssi, String source) {
        if (d == null) return;
        String name = safeName(d);
        if (!isT800(name)) return;
        String addr;
        try { addr = d.getAddress(); } catch (Throwable e) { return; }
        devices.put(addr, d);
        if (!emitted.add(addr)) return;
        int bond = BluetoothDevice.BOND_NONE;
        try { if (hasBtPermissions()) bond = d.getBondState(); } catch (Throwable ignored) {}
        js("onBtDevice", name.isEmpty() ? "T800 Ultra2" : name, addr, String.valueOf(rssi), source, bond == BluetoothDevice.BOND_BONDED ? "Pareado" : "Não pareado");
    }

    private void emitBondedT800() {
        if (btAdapter == null || !hasBtPermissions()) return;
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) emitDevice(d, 0, "Android");
        } catch (Throwable ignored) {}
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String name = safeName(d);
            if (name.isEmpty() && result.getScanRecord() != null) {
                String recordName = result.getScanRecord().getDeviceName();
                if (recordName != null) name = recordName;
            }
            if (!isT800(name)) return;
            try { devices.put(d.getAddress(), d); } catch (Throwable ignored) {}
            emitDevice(d, result.getRssi(), "BLE");
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            js("onBtScanFailed", "Falha na busca BLE (código " + errorCode + "). Desligue e ligue o Bluetooth do celular.");
        }
    };

    private void connectGattNow(BluetoothDevice d) {
        if (d == null) { js("onBtConnectionFailed", "T800 Ultra2 não encontrado."); return; }
        currentDevice = d;
        currentName = safeName(d);
        try { currentAddress = d.getAddress(); } catch (Throwable e) { currentAddress = ""; }
        connecting = true;
        connected = false;
        handler.removeCallbacks(connectTimeout);
        js("onBtConnecting", currentName.isEmpty() ? "T800 Ultra2" : currentName, currentAddress);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = d.connectGatt(this, false, gattCallback);
            handler.postDelayed(connectTimeout, 20000);
        } catch (Throwable e) {
            connecting = false;
            js("onBtConnectionFailed", "Android não conseguiu abrir a conexão BLE: " + e.getClass().getSimpleName());
        }
    }

    private void retryAfter133(BluetoothDevice d) {
        if (retried133 || d == null) return;
        retried133 = true;
        js("onBtRetry", "O Android retornou GATT 133. Tentando novamente uma vez…");
        BluetoothGatt old = gatt; gatt = null;
        try { if (old != null) { refreshDeviceCache(old); old.close(); } } catch (Throwable ignored) {}
        handler.postDelayed(() -> connectGattNow(d), 1400);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.removeCallbacks(connectTimeout);
                connecting = false; connected = false;
                if (status == 133 && !retried133) { retryAfter133(currentDevice); return; }
                js("onBtConnectionFailed", "O T800 recusou/encerrou a conexão. Erro GATT " + status + ". Feche o HIwatch Pro e tente novamente.");
                try { g.close(); } catch (Throwable ignored) {}
                if (gatt == g) gatt = null;
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                js("onBtTransportConnected", currentName.isEmpty() ? "T800 Ultra2" : currentName);
                try {
                    if (hasBtPermissions() && g.discoverServices()) return;
                } catch (Throwable ignored) {}
                js("onBtConnectionFailed", "Bluetooth conectou, mas o T800 não iniciou a leitura dos serviços.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(connectTimeout);
                connecting = false; connected = false;
                js("onBtDisconnected", "T800 Ultra2 desconectado.");
                try { g.close(); } catch (Throwable ignored) {}
                if (gatt == g) gatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.removeCallbacks(connectTimeout);
            connecting = false;
            if (status != BluetoothGatt.GATT_SUCCESS || g.getServices() == null || g.getServices().isEmpty()) {
                connected = false;
                js("onBtConnectionFailed", "Conexão BLE aberta, porém o T800 não expôs serviços GATT.");
                return;
            }
            int services = 0, chars = 0, writable = 0, notify = 0;
            StringBuilder uuids = new StringBuilder();
            for (BluetoothGattService s : g.getServices()) {
                services++;
                if (uuids.length() < 5000) uuids.append("S ").append(s.getUuid()).append("\n");
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    chars++;
                    int p = c.getProperties();
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writable++;
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify++;
                    if (uuids.length() < 5000) uuids.append("  C ").append(c.getUuid()).append(" p=").append(p).append("\n");
                }
            }
            connected = true;
            js("onBtReady", currentName.isEmpty() ? "T800 Ultra2" : currentName, String.valueOf(services), String.valueOf(chars), String.valueOf(writable), String.valueOf(notify), uuids.toString().trim());
            try {
                if (Build.VERSION.SDK_INT >= 21 && hasBtPermissions()) g.requestMtu(247);
                BluetoothGattService battery = g.getService(BATTERY_SERVICE);
                if (battery != null) {
                    BluetoothGattCharacteristic level = battery.getCharacteristic(BATTERY_LEVEL);
                    if (level != null && hasBtPermissions()) g.readCharacteristic(level);
                }
            } catch (Throwable ignored) {}
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                try {
                    Integer v = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    if (v != null) js("onBattery", String.valueOf(v));
                } catch (Throwable ignored) {}
            }
        }
    };

    private void refreshDeviceCache(BluetoothGatt g) {
        try {
            Method m = g.getClass().getMethod("refresh");
            m.setAccessible(true);
            m.invoke(g);
        } catch (Throwable ignored) {}
    }

    private void stopScanInternal() {
        if (scanning && scanner != null) {
            try { if (hasBtPermissions()) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
        scanning = false;
    }

    private void safeCloseGatt() {
        handler.removeCallbacks(connectTimeout);
        connecting = false; connected = false;
        BluetoothGatt local = gatt; gatt = null;
        if (local != null) {
            try { if (hasBtPermissions()) local.disconnect(); } catch (Throwable ignored) {}
            try { local.close(); } catch (Throwable ignored) {}
        }
        currentDevice = null;
        currentName = "";
        currentAddress = "";
        retried133 = false;
    }

    private boolean savePngDataUrl(String dataUrl, String filename) {
        if (dataUrl == null || !dataUrl.contains(",")) return false;
        try {
            String base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            String safe = (filename == null || filename.trim().isEmpty()) ? "MusyFit-T800.png" : filename.replaceAll("[^a-zA-Z0-9._-]", "-");
            if (!safe.toLowerCase(Locale.ROOT).endsWith(".png")) safe += ".png";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, safe);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MusyFit Watch");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            try (OutputStream out = getContentResolver().openOutputStream(uri); ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                if (out == null) return false;
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues(); done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            return true;
        } catch (Throwable e) { return false; }
    }

    public class Bridge {
        @JavascriptInterface public void requestPermissions() { runOnUiThread(MainActivity.this::requestBtPermissions); }
        @JavascriptInterface public String bluetoothState() {
            if (btAdapter == null) return "unsupported";
            if (!hasBtPermissions()) return "permission";
            try { return btAdapter.isEnabled() ? "on" : "off"; } catch (Throwable e) { return "off"; }
        }
        @JavascriptInterface public void startScan() {
            runOnUiThread(() -> {
                if (!hasBtPermissions()) { requestBtPermissions(); js("onBtStatus", "Autorize Bluetooth e toque em Buscar T800 novamente."); return; }
                if (btAdapter == null || !btAdapter.isEnabled()) { js("onBtStatus", "Ative o Bluetooth do celular."); return; }
                stopScanInternal(); safeCloseGatt(); devices.clear(); emitted.clear();
                emitBondedT800();
                scanner = btAdapter.getBluetoothLeScanner();
                if (scanner == null) { js("onBtScanFailed", "Busca BLE indisponível neste celular."); return; }
                scanning = true;
                try { scanner.startScan(scanCallback); }
                catch (Throwable e) { scanning = false; js("onBtScanFailed", "Não foi possível iniciar a busca BLE."); return; }
                js("onBtScanning", "Procurando somente T800 Ultra2…");
                handler.postDelayed(() -> { stopScanInternal(); js("onBtScanFinished", String.valueOf(devices.size())); }, 15000);
            });
        }
        @JavascriptInterface public void connect(String address) {
            runOnUiThread(() -> {
                if (!hasBtPermissions()) { requestBtPermissions(); return; }
                BluetoothDevice d = devices.get(address);
                if (d == null && btAdapter != null) {
                    try { d = btAdapter.getRemoteDevice(address); } catch (Throwable ignored) {}
                }
                if (d == null || !isT800(safeName(d))) { js("onBtConnectionFailed", "Esse dispositivo não foi identificado como T800 Ultra2."); return; }
                stopScanInternal();
                BluetoothGatt old = gatt; gatt = null;
                if (old != null) { try { old.close(); } catch (Throwable ignored) {} }
                retried133 = false;
                connectGattNow(d);
            });
        }
        @JavascriptInterface public void pair(String address) {
            runOnUiThread(() -> {
                if (!hasBtPermissions()) { requestBtPermissions(); return; }
                BluetoothDevice d = devices.get(address);
                if (d == null && btAdapter != null) { try { d = btAdapter.getRemoteDevice(address); } catch (Throwable ignored) {} }
                if (d == null) { js("onPairState", "T800 não encontrado. Faça nova busca.", "none"); return; }
                try {
                    if (d.getBondState() == BluetoothDevice.BOND_BONDED) { js("onPairState", "T800 já está pareado no Android.", "bonded"); return; }
                    boolean ok = d.createBond();
                    js("onPairState", ok ? "Pareamento iniciado. Confirme no relógio/celular." : "O Android não iniciou o pareamento.", ok ? "bonding" : "none");
                } catch (Throwable e) { js("onPairState", "Falha ao iniciar pareamento: " + e.getClass().getSimpleName(), "none"); }
            });
        }
        @JavascriptInterface public void disconnect() { runOnUiThread(() -> { safeCloseGatt(); js("onBtDisconnected", "Desconectado."); }); }
        @JavascriptInterface public void savePng(String dataUrl, String filename) {
            runOnUiThread(() -> {
                boolean ok = savePngDataUrl(dataUrl, filename);
                js(ok ? "onImageSaved" : "onImageSaveError", ok ? "Imagem salva em Fotos > MusyFit Watch." : "Não foi possível salvar a imagem.");
            });
        }
        @JavascriptInterface public String appVersion() { return "3.1"; }
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(req, permissions, grants);
        if (req == REQ_BT) js("onBtStatus", hasBtPermissions() ? "Bluetooth autorizado. Toque em Buscar T800." : "Permissão Bluetooth não concedida.");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && chooser != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{data.getData()};
            try { chooser.onReceiveValue(out); } catch (Throwable ignored) {}
            chooser = null;
        }
    }

    @Override protected void onDestroy() {
        stopScanInternal(); safeCloseGatt(); handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(bondReceiver); } catch (Throwable ignored) {}
        if (webView != null) {
            try { webView.removeJavascriptInterface("MusyFitBridge"); webView.stopLoading(); webView.destroy(); } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
