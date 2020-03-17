package com.balthazargronon.RCTZeroconf;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import javax.annotation.Nullable;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.io.UnsupportedEncodingException;

/**
 * Created by Jeremy White on 8/1/2016.
 * Copyright © 2016 Balthazar Gronon MIT
 */
public class ZeroconfModule extends ReactContextBaseJavaModule {

    public static final String EVENT_START = "RNZeroconfStart";
    public static final String EVENT_STOP = "RNZeroconfStop";
    public static final String EVENT_ERROR = "RNZeroconfError";
    public static final String EVENT_FOUND = "RNZeroconfFound";
    public static final String EVENT_REMOVE = "RNZeroconfRemove";
    public static final String EVENT_RESOLVE = "RNZeroconfResolved";

    public static final String EVENT_REGISTER = "RNZeroconfServiceRegistered";
    public static final String EVENT_UNREGISTER = "RNZeroconfServiceUnregistered";

    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_SERVICE_NAME = "name";
    public static final String KEY_SERVICE_FULL_NAME = "fullName";
    public static final String KEY_SERVICE_HOST = "host";
    public static final String KEY_SERVICE_PORT = "port";
    public static final String KEY_SERVICE_ADDRESSES = "addresses";
    public static final String KEY_SERVICE_TXT = "txt";

    protected NsdManager mNsdManager;
    protected NsdManager.DiscoveryListener mDiscoveryListener;
    protected WifiManager.MulticastLock multicastLock;

    protected Map<String, NsdManager.RegistrationListener> mPublishedServices;

    public ZeroconfModule(ReactApplicationContext reactContext) {

        super(reactContext);
        mPublishedServices = new HashMap<String, NsdManager.RegistrationListener>();
    }

    protected
    NsdManager getNsdManager() {
        if (mNsdManager == null) {
            mNsdManager = (NsdManager) getReactApplicationContext().getSystemService(Context.NSD_SERVICE);
        }
        return mNsdManager;
    }

    @Override
    public String getName() {
        return "RNZeroconf";
    }

    @ReactMethod
    public void scan(String type, String protocol, String domain) {
        if (mNsdManager == null) {
            mNsdManager = (NsdManager) getReactApplicationContext().getSystemService(Context.NSD_SERVICE);
        }

        this.stop();

        if (multicastLock == null) {
            WifiManager wifi = (WifiManager) getReactApplicationContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicastLock = wifi.createMulticastLock("multicastLock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                String error = "Starting service discovery failed with code: " + errorCode;
                sendEvent(getReactApplicationContext(), EVENT_START, error);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                String error = "Stopping service discovery failed with code: " + errorCode;
                sendEvent(getReactApplicationContext(), EVENT_STOP, error);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                sendEvent(getReactApplicationContext(), EVENT_START, null);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                sendEvent(getReactApplicationContext(), EVENT_STOP, null);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                WritableMap service = new WritableNativeMap();
                service.putString(KEY_SERVICE_NAME, serviceInfo.getServiceName());

                sendEvent(getReactApplicationContext(), EVENT_FOUND, service);
                mNsdManager.resolveService(serviceInfo, new ZeroResolveListener());
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                WritableMap service = new WritableNativeMap();
                service.putString(KEY_SERVICE_NAME, serviceInfo.getServiceName());
                sendEvent(getReactApplicationContext(), EVENT_REMOVE, service);
            }
        };

        String serviceType = String.format("_%s._%s.", type, protocol);
        mNsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
    }

    @ReactMethod
    public void stop() {
        if (mDiscoveryListener != null) {
            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
        }
        if (multicastLock != null) {
            multicastLock.release();
        }
        mDiscoveryListener = null;
        multicastLock = null;
    }

    @ReactMethod
    public void registerService(String type, String protocol, String domain, String name, int port) {
        String serviceType = String.format("_%s._%s.", type, protocol);

        final NsdManager nsdManager = this.getNsdManager();
        NsdServiceInfo serviceInfo  = new NsdServiceInfo();
        serviceInfo.setServiceName(name);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setPort(port);

        nsdManager.registerService(
                serviceInfo, NsdManager.PROTOCOL_DNS_SD, new ServiceRegistrationListener());
    }

    @ReactMethod
    public void unregisterService(String serviceName) {

        final NsdManager nsdManager = this.getNsdManager();

        NsdManager.RegistrationListener serviceListener = mPublishedServices.get(serviceName);

        if (serviceListener != null) {
            mPublishedServices.remove(serviceName);
            nsdManager.unregisterService(serviceListener);
        }
    }


    protected void sendEvent(ReactContext reactContext,
                             String eventName,
                             @Nullable Object params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    protected
    WritableMap serviceInfoToMap(NsdServiceInfo serviceInfo, int errorCode) {
        WritableMap service = new WritableNativeMap();
        service.putInt(KEY_ERROR_CODE, errorCode);
        service.putString(KEY_SERVICE_NAME, serviceInfo.getServiceName());
        final InetAddress host = serviceInfo.getHost();
        final String fullServiceName;
        if (host == null) {
            fullServiceName = serviceInfo.getServiceName();
        } else {
            fullServiceName = host.getHostName() + serviceInfo.getServiceType();
            service.putString(KEY_SERVICE_HOST, host.getHostName());

            WritableArray addresses = new WritableNativeArray();
            addresses.pushString(host.getHostAddress());

            service.putArray(KEY_SERVICE_ADDRESSES, addresses);
        }
        service.putString(KEY_SERVICE_FULL_NAME, fullServiceName);
        service.putInt(KEY_SERVICE_PORT, serviceInfo.getPort());

        WritableMap txtRecords = new WritableNativeMap();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Map<String, byte[]> attributes = serviceInfo.getAttributes();
            for (String key : attributes.keySet()) {
                try {
                    byte[] recordValue = attributes.get(key);
                    txtRecords.putString(String.format(Locale.getDefault(), "%s", key), String.format(Locale.getDefault(), "%s", recordValue != null ? new String(recordValue, "UTF_8") : ""));
                } catch (UnsupportedEncodingException e) {
                    String error = "Failed to encode txtRecord: " + e;
                    sendEvent(getReactApplicationContext(), EVENT_ERROR, error);
                }
            }
        }

        service.putMap(KEY_SERVICE_TXT, txtRecords);

        return service;
    }

    private class ZeroResolveListener implements NsdManager.ResolveListener {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                mNsdManager.resolveService(serviceInfo, this);
            } else {
                WritableMap service = serviceInfoToMap(serviceInfo, errorCode);
                sendEvent(getReactApplicationContext(), EVENT_RESOLVE, service);
            }
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            WritableMap service = serviceInfoToMap(serviceInfo, 0);
            sendEvent(getReactApplicationContext(), EVENT_RESOLVE, service);
        }
    }

    private class ServiceRegistrationListener implements NsdManager.RegistrationListener {

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            // Save the service name.  Android may have changed it in order to
            // resolve a conflict, so update the name you initially requested
            // with the name Android actually used.

            final String serviceName = serviceInfo.getServiceName();
            mPublishedServices.put(serviceName, this);

            WritableMap service = serviceInfoToMap(serviceInfo, 0);
            sendEvent(getReactApplicationContext(), EVENT_REGISTER, service);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            final WritableMap service = serviceInfoToMap(serviceInfo, errorCode);
            sendEvent(getReactApplicationContext(), EVENT_REGISTER, service);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            // Service has been unregistered.  This only happens when you call
            // NsdManager.unregisterService() and pass in this listener.
            final WritableMap service = serviceInfoToMap(serviceInfo, 0);
            sendEvent(getReactApplicationContext(), EVENT_UNREGISTER, service);
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            final WritableMap service = serviceInfoToMap(serviceInfo, errorCode);
            sendEvent(getReactApplicationContext(), EVENT_UNREGISTER, service);
        }
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        stop();
    }
}
