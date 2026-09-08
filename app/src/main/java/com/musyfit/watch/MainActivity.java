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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
  private static final int REQ_FILE = 77;
  private static final int REQ_BT = 101;
  private WebView webView;
  private ValueCallback<Uri[]> chooser;
  private BluetoothAdapter btAdapter;
  private BluetoothLeScanner scanner;
  private BluetoothGatt gatt;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, BluetoothDevice> devices = new HashMap<>();
  private boolean scanning = false;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    webView = new WebView(this);
    setContentView(webView);
    webView.getSettings().setJavaScriptEnabled(true);
    webView.getSettings().setDomStorageEnabled(true);
    webView.getSettings().setAllowFileAccess(true);
    webView.setWebViewClient(new WebViewClient());
    webView.addJavascriptInterface(new BtBridge(), "MusyFitBluetooth");
    webView.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
        chooser = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_FILE);
        return true;
      }
    });

    BluetoothManager bm = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
    btAdapter = bm != null ? bm.getAdapter() : null;
    webView.loadUrl("file:///android_asset/index.html");
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
    StringBuilder sb = new StringBuilder("javascript:").append(function).append("(");
    for (int i=0; i<args.length; i++) {
      if (i>0) sb.append(',');
      String s = args[i] == null ? "" : args[i];
      sb.append('"').append(s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")).append('"');
    }
    sb.append(')');
    runOnUiThread(() -> webView.evaluateJavascript(sb.toString(), null));
  }

  private final ScanCallback scanCallback = new ScanCallback() {
    @Override public void onScanResult(int callbackType, ScanResult result) {
      BluetoothDevice d = result.getDevice();
      if (d == null) return;
      String name = "";
      try { if (hasBtPermissions() && d.getName()!=null) name = d.getName(); } catch (SecurityException ignored) {}
      String addr = d.getAddress();
      if (!devices.containsKey(addr)) {
        devices.put(addr, d);
        js("onBtDevice", name, addr, String.valueOf(result.getRssi()));
      }
    }
    @Override public void onScanFailed(int errorCode) { js("onBtStatus", "Falha na busca Bluetooth: código " + errorCode); }
  };

  private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
    @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
      if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
        js("onBtStatus", "Conectado por Bluetooth. Descobrindo serviços do relógio…");
        try { if (hasBtPermissions()) g.discoverServices(); } catch (SecurityException e) { js("onBtStatus", "Permissão Bluetooth necessária."); }
      } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
        js("onBtStatus", "Relógio desconectado.");
      }
    }
    @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
      int services = g.getServices() == null ? 0 : g.getServices().size();
      int chars = 0;
      if (g.getServices()!=null) for (BluetoothGattService s : g.getServices()) chars += s.getCharacteristics().size();
      js("onBtReady", String.valueOf(services), String.valueOf(chars));
    }
  };

  public class BtBridge {
    @JavascriptInterface public void requestPermissions() { runOnUiThread(() -> requestBtPermissions()); }
    @JavascriptInterface public String bluetoothState() {
      if (btAdapter == null) return "unsupported";
      if (!hasBtPermissions()) return "permission";
      return btAdapter.isEnabled() ? "on" : "off";
    }
    @JavascriptInterface public void startScan() {
      runOnUiThread(() -> {
        if (!hasBtPermissions()) { requestBtPermissions(); js("onBtStatus", "Autorize o Bluetooth e toque em Buscar novamente."); return; }
        if (btAdapter == null || !btAdapter.isEnabled()) { js("onBtStatus", "Ative o Bluetooth do celular."); return; }
        scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) { js("onBtStatus", "Bluetooth LE indisponível."); return; }
        devices.clear(); scanning = true;
        try { scanner.startScan(scanCallback); } catch (SecurityException e) { js("onBtStatus", "Sem permissão para buscar dispositivos."); return; }
        js("onBtStatus", "Buscando Redmi Watch 5 Active próximo…");
        handler.postDelayed(() -> stopScanInternal(), 12000);
      });
    }
    @JavascriptInterface public void stopScan() { runOnUiThread(() -> stopScanInternal()); }
    @JavascriptInterface public void connect(String address) {
      runOnUiThread(() -> {
        if (!hasBtPermissions()) { requestBtPermissions(); return; }
        BluetoothDevice d = devices.get(address);
        if (d == null && btAdapter != null) {
          try { d = btAdapter.getRemoteDevice(address); } catch (Exception ignored) {}
        }
        if (d == null) { js("onBtStatus", "Dispositivo não encontrado. Faça uma nova busca."); return; }
        stopScanInternal();
        if (gatt != null) { try { gatt.close(); } catch(Exception ignored){} }
        js("onBtStatus", "Conectando ao relógio…");
        try { gatt = d.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE); }
        catch (SecurityException e) { js("onBtStatus", "Permissão Bluetooth necessária."); }
      });
    }
    @JavascriptInterface public void disconnect() {
      runOnUiThread(() -> {
        if (gatt != null) { try { if (hasBtPermissions()) gatt.disconnect(); gatt.close(); } catch(Exception ignored){} gatt = null; }
        js("onBtStatus", "Desconectado.");
      });
    }
  }

  private void stopScanInternal() {
    if (scanning && scanner != null) {
      try { if (hasBtPermissions()) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
    }
    scanning = false;
  }

  @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(req, permissions, grants);
    if (req == REQ_BT) js("onBtStatus", hasBtPermissions() ? "Bluetooth autorizado. Agora toque em Buscar relógio." : "Permissão Bluetooth não concedida.");
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req,res,data);
    if(req==REQ_FILE && chooser!=null) {
      Uri[] out=null;
      if(res==RESULT_OK && data!=null && data.getData()!=null) out=new Uri[]{data.getData()};
      chooser.onReceiveValue(out); chooser=null;
    }
  }

  @Override protected void onDestroy() {
    stopScanInternal();
    if (gatt != null) { try { if (hasBtPermissions()) gatt.disconnect(); gatt.close(); } catch(Exception ignored){} }
    super.onDestroy();
  }

  @Override public void onBackPressed(){ if(webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
}
