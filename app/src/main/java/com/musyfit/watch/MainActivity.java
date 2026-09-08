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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 77;
    private static final int REQ_BT = 101;
    private static final int REQ_STORAGE = 102;
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private ValueCallback<Uri[]> chooser;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> devices = new HashMap<>();
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private String currentName = "";
    private String currentAddress = "";

    private final Runnable connectTimeout = () -> {
        if (connecting || !connected) {
            js("onBtConnectionFailed", "Tempo esgotado. O Android não conseguiu validar uma sessão Bluetooth com este relógio.");
            safeCloseGatt();
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            webView = new WebView(this);
            setContentView(webView);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setAllowFileAccess(true);
            webView.getSettings().setAllowContentAccess(true);
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
                    } catch (Exception e) {
                        chooser = null;
                        return false;
                    }
                }
            });
            BluetoothManager bm = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            btAdapter = bm != null ? bm.getAdapter() : null;
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Throwable t) {
            finish();
        }
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
            String s = args[i] == null ? "" : args[i];
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")).append('"');
        }
        sb.append(')');
        runOnUiThread(() -> {
            try {
                if (!isFinishing() && webView != null) webView.evaluateJavascript(sb.toString(), null);
            } catch (Throwable ignored) {}
        });
    }

    private String safeName(BluetoothDevice d) {
        try {
            if (d != null && hasBtPermissions() && d.getName() != null) return d.getName();
        } catch (Throwable ignored) {}
        return "";
    }

    private String profileFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("t800") || n.contains("ultra2") || n.contains("ultra 2")) return "T800 Ultra2";
        if (n.contains("redmi") || n.contains("watch 5 active") || n.contains("m2460")) return "Redmi Watch 5 Active";
        return "Outro relógio BLE";
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String addr;
            try { addr = d.getAddress(); } catch (Throwable e) { return; }
            String name = safeName(d);
            if (name.isEmpty() && result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            devices.put(addr, d);
            js("onBtDevice", name, addr, String.valueOf(result.getRssi()), profileFor(name));
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            js("onBtScanFailed", "Falha na busca Bluetooth (código " + errorCode + "). Reinicie o Bluetooth e tente novamente.");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false; connected = false;
                handler.removeCallbacks(connectTimeout);
                js("onBtConnectionFailed", "Conexão recusada/encerrada pelo relógio. Erro GATT " + status + ".");
                try { g.close(); } catch (Throwable ignored) {}
                if (gatt == g) gatt = null;
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                js("onBtTransportConnected", currentName, profileFor(currentName));
                try {
                    if (hasBtPermissions() && g.discoverServices()) return;
                } catch (Throwable ignored) {}
                js("onBtConnectionFailed", "Bluetooth conectou, mas a descoberta de serviços não iniciou.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting = false; connected = false;
                handler.removeCallbacks(connectTimeout);
                js("onBtDisconnected", "Relógio desconectado.");
                try { g.close(); } catch (Throwable ignored) {}
                if (gatt == g) gatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.removeCallbacks(connectTimeout);
            if (status != BluetoothGatt.GATT_SUCCESS || g.getServices() == null || g.getServices().isEmpty()) {
                connected = false;
                js("onBtConnectionFailed", "Sessão Bluetooth aberta, porém sem serviços GATT acessíveis.");
                return;
            }
            int services = 0, chars = 0, writable = 0, notify = 0;
            StringBuilder uuids = new StringBuilder();
            for (BluetoothGattService s : g.getServices()) {
                services++;
                if (uuids.length() < 1800) uuids.append("S ").append(s.getUuid()).append("\n");
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    chars++;
                    int p = c.getProperties();
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writable++;
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify++;
                    if (uuids.length() < 1800) uuids.append("  C ").append(c.getUuid()).append(" p=").append(p).append("\n");
                }
            }
            connected = true;
            js("onBtReady", currentName, profileFor(currentName), String.valueOf(services), String.valueOf(chars), String.valueOf(writable), String.valueOf(notify), uuids.toString().trim());
            try {
                BluetoothGattService battery = g.getService(BATTERY_SERVICE);
                if (battery != null) {
                    BluetoothGattCharacteristic level = battery.getCharacteristic(BATTERY_LEVEL);
                    if (level != null && hasBtPermissions()) g.readCharacteristic(level);
                }
            } catch (Throwable ignored) {}
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(characteristic.getUuid())) {
                try {
                    Integer v = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    if (v != null) js("onBattery", String.valueOf(v));
                } catch (Throwable ignored) {}
            }
        }
    };

    private void safeCloseGatt() {
        handler.removeCallbacks(connectTimeout);
        connecting = false; connected = false;
        BluetoothGatt local = gatt; gatt = null;
        if (local != null) {
            try { if (hasBtPermissions()) local.disconnect(); } catch (Throwable ignored) {}
            try { local.close(); } catch (Throwable ignored) {}
        }
        currentName = ""; currentAddress = "";
    }

    private void stopScanInternal() {
        if (scanning && scanner != null) {
            try { if (hasBtPermissions()) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
        scanning = false;
    }

    private boolean savePngDataUrl(String dataUrl, String filename) {
        if (dataUrl == null || !dataUrl.contains(",")) return false;
        try {
            String base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            String safe = (filename == null || filename.trim().isEmpty()) ? "MusyFit-Watch.png" : filename.replaceAll("[^a-zA-Z0-9._-]", "-");
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

    private void openPackageOrStore(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) { startActivity(launch); return; }
        } catch (Throwable ignored) {}
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))); }
        catch (Throwable e) { js("onExternalAppError", "Não foi possível abrir o aplicativo companheiro."); }
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
                if (!hasBtPermissions()) { requestBtPermissions(); js("onBtStatus", "Autorize Bluetooth e tente novamente."); return; }
                if (btAdapter == null || !btAdapter.isEnabled()) { js("onBtStatus", "Ative o Bluetooth do celular."); return; }
                stopScanInternal(); devices.clear();
                scanner = btAdapter.getBluetoothLeScanner();
                if (scanner == null) { js("onBtScanFailed", "Scanner BLE indisponível."); return; }
                scanning = true;
                try { scanner.startScan(scanCallback); }
                catch (Throwable e) { scanning = false; js("onBtScanFailed", "Não foi possível iniciar a busca BLE."); return; }
                js("onBtScanning", "Buscando Redmi Watch 5 Active, T800 Ultra2 e outros dispositivos BLE…");
                handler.postDelayed(() -> { stopScanInternal(); js("onBtScanFinished", String.valueOf(devices.size())); }, 12000);
            });
        }
        @JavascriptInterface public void connect(String address) {
            runOnUiThread(() -> {
                if (!hasBtPermissions()) { requestBtPermissions(); return; }
                if (address == null || address.trim().isEmpty()) { js("onBtConnectionFailed", "Endereço Bluetooth inválido."); return; }
                BluetoothDevice d = devices.get(address);
                if (d == null && btAdapter != null) {
                    try { d = btAdapter.getRemoteDevice(address); } catch (Throwable ignored) {}
                }
                if (d == null) { js("onBtConnectionFailed", "Dispositivo não encontrado. Faça uma nova busca."); return; }
                stopScanInternal(); safeCloseGatt();
                currentName = safeName(d); currentAddress = address;
                connecting = true; connected = false;
                js("onBtConnecting", currentName, address, profileFor(currentName));
                try {
                    gatt = d.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    handler.postDelayed(connectTimeout, 18000);
                } catch (Throwable e) { connecting = false; js("onBtConnectionFailed", "O Android não conseguiu abrir a sessão GATT."); }
            });
        }
        @JavascriptInterface public void disconnect() { runOnUiThread(() -> { safeCloseGatt(); js("onBtDisconnected", "Desconectado."); }); }
        @JavascriptInterface public void scanQr() {
            runOnUiThread(() -> {
                try {
                    IntentIntegrator integrator = new IntentIntegrator(MainActivity.this);
                    integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
                    integrator.setPrompt("Aponte para o QR Code"); integrator.setBeepEnabled(false); integrator.setOrientationLocked(false);
                    integrator.initiateScan();
                } catch (Throwable e) { js("onQrError", "Não foi possível abrir o leitor de QR."); }
            });
        }
        @JavascriptInterface public String generateQr(String text) {
            if (text == null || text.trim().isEmpty()) return "";
            try {
                int size = 420; BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565); int[] pixels = new int[size * size];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                bmp.setPixels(pixels, 0, size, 0, 0, size, size);
                ByteArrayOutputStream out = new ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable e) { return ""; }
        }
        @JavascriptInterface public void savePng(String dataUrl, String filename) {
            runOnUiThread(() -> {
                boolean ok = savePngDataUrl(dataUrl, filename);
                if (ok) js("onImageSaved", "Imagem salva em Fotos > MusyFit Watch.");
                else js("onImageSaveError", "Não foi possível salvar a imagem.");
            });
        }
        @JavascriptInterface public void openCompanion(String profile) {
            runOnUiThread(() -> {
                if (profile != null && profile.toLowerCase(Locale.ROOT).contains("t800")) openPackageOrStore("com.legend.hiwatchpro.app");
                else openPackageOrStore("com.xiaomi.wearable");
            });
        }
        @JavascriptInterface public String appVersion() { return "3.0"; }
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(req, permissions, grants);
        if (req == REQ_BT) js("onBtStatus", hasBtPermissions() ? "Bluetooth autorizado. Toque em Buscar relógios." : "Permissão Bluetooth não concedida.");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        try {
            IntentResult qr = IntentIntegrator.parseActivityResult(req, res, data);
            if (qr != null) {
                if (qr.getContents() != null) js("onQrScanned", qr.getContents()); else js("onQrError", "Leitura de QR cancelada.");
                return;
            }
        } catch (Throwable ignored) {}
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
        if (webView != null) {
            try { webView.removeJavascriptInterface("MusyFitBridge"); webView.stopLoading(); webView.destroy(); } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
