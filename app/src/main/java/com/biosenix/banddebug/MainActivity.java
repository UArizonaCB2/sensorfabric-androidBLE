/*
Notes - Path where the new android 11 scoped external storage will store files is
/sdcard/Android/data/com.biosenix.banddebug/files
 */

package com.biosenix.banddebug;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.biosenix.banddebug.aws.Kinesis;
import com.biosenix.banddebug.models.Acceleration;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private BluetoothLeScanner bluetoothLeScanner;
    private boolean bleScanning = false;
    private boolean bleConnected = false;
    private String deviceId = "00";
    private BluetoothDevice bleDevice = null;
    private BluetoothGatt bluetoothGatt = null;
    private BluetoothGattService biosenixService = null;

    // Service UUID for Biosenix custom service.
    private final String serviceUUID = "f3641400-00b0-4240-ba50-05ca45bf8abc";  // Custom Service
    private final String accCharUUID = "f3641404-00b0-4240-ba50-05ca45bf8abc";  // Acc characteristic
    private final String ppgCharUUID = "f3641403-00b0-4240-ba50-05ca45bf8abc";  // PPG characteristic

    // BLE Descriptors of acc and PPG notifications. They are currently the same.
    private final String notificationDesciptors = "00002902-0000-1000-8000-00805f9b34fb";

    // This activity context so we can use it to run UI thread updates.
    private Activity activity;

    // UI elements that need to be changed with the state of the activity.
    Button btnConnect = null;

    // Store if external storage permission as given.
    boolean hasStoragePermission = false;
    // Stores the file descriptor.
    File dataFile = null;
    BufferedOutputStream ofStream = null;

    // The start timestamp always stores the first timestamp of the BLE entry that comes in.
    int startDataTimestamp = -1;
    int lastTimeStamp = -1; // To avoid duplicate values.

    enum Button_state {
        BUTT_CONNECT,
        BUTT_DISCONNECT
    }

    // AWS Kinesis Wrappers.
    Kinesis kinesis;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EditText numDeviceId = findViewById(R.id.numDeviceID);
        btnConnect = findViewById(R.id.btnConnect);
        activity = this;

        ArrayList<String> permissions = new ArrayList<>();

        // Check for run-time permissions for BLE and request them.
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            // Good we have permission. Nothing to see here then!
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Run-time permission check for external storage.
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            // Good we have permission. Nothing to see here then!
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE);
        }

        // If we have permissions to ask for, ask them here.
        if(permissions.size() > 0) {
            String[] perms = new String[permissions.size()];
            requestPermissions(permissions.toArray(perms), 101);
        }

        // Add event listener to the button.
        btnConnect.setOnClickListener(new View.OnClickListener() {

            @SuppressLint("MissingPermission")
            @Override
            public void onClick(View view) {
                if(!bleConnected) {
                    String _deviceId = numDeviceId.getText().toString();
                    deviceId = _deviceId;
                    updateStatus("Looking for BP" + deviceId);
                    // Scan the BLE scanning.
                    scanBLEDevices();
                }
                else {
                    if(bluetoothGatt != null) {
                        bluetoothGatt.disconnect();
                    }
                }
            }
        });

        // Create a new Kinesis stream that we can connect to.
        kinesis = new Kinesis("acceleration", "us-east-1");
    }

    // Permission results callback.

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Bluetooth permission.
        if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // All good nothing to do here.
        }
        else {
            updateStatus("BLE Permission Denied.");
        }

        // External storage permission.
        if(grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            hasStoragePermission = true;
        }
        else {
            // Ohh well time to keep moving on and not save to a file.
            Log.d("Permission", "External Storage Denied");
        }
    }

    // Run this on the main activity UI thread.
    private void updateButton(Button_state state) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(btnConnect == null) {
                    return;
                }

                switch (state) {
                    case BUTT_CONNECT:
                        btnConnect.setText("Connect");
                        break;

                    case BUTT_DISCONNECT:
                        btnConnect.setText("Disconnect");
                        break;
                }
            }
        });
    }

    // Run this on the main UI thread.
    private void updateStatus(String message) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView txtStatus = findViewById(R.id.txtStatus);
                txtStatus.setText(message);
            }
        });
    }

    // Method which creates a new file to write data into named on the current data and time.
    private void createFile() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        String filename = dtf.format(now) + ".csv";

        // Check if external storage is present.
        String state = Environment.getExternalStorageState();
        if(Environment.MEDIA_MOUNTED.equals(state)) {
            try {
                File[] externalStorageVolumes = ContextCompat.getExternalFilesDirs(getApplicationContext(), null);
                if(externalStorageVolumes.length > 0) {
                    this.dataFile = new File(externalStorageVolumes[0] + "/" + filename);
                    this.dataFile.createNewFile();
                    this.ofStream = new BufferedOutputStream(new FileOutputStream(this.dataFile));
                    // Also reset the start timestamp.
                    startDataTimestamp = -1;
                    lastTimeStamp = -1;
                }
            }
            catch (IOException ex) {
                // Cannot create the new file.
                Log.e("FILE", "Cannot create file", ex);
            }
        }
    }

    // Method to do BLE scanning.
    @SuppressLint("MissingPermission")
    private void scanBLEDevices() {
        if(!bleScanning) {
            bluetoothLeScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

            // Automatically stop scanning after a certain time.
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    bluetoothLeScanner.stopScan(leScanCallback);
                    bleScanning = false;
                }
            }, 10 * 1000);  // Run for 10 seconds.

            bluetoothLeScanner.startScan(leScanCallback);
            bleScanning = true;
        }
        else {
            // Stop BLE Scanning. We the same method for it.
            bluetoothLeScanner.stopScan(leScanCallback);
            bleScanning = false;
        }
    }

    @SuppressLint("MissingPermission")
    private ScanCallback leScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
                    if(name != null) {
                        // Note - We do a string search here since direct string matching was not working.
                        if(name.indexOf("BP"+deviceId) >= 0 && bleScanning) {
                            bleScanning = false;    // We don't want to come back here again.
                            updateStatus("Found BP"+deviceId+". Connecting ..");
                            bleDevice = device;
                            // Attempt to connect to the GATT server.
                            bluetoothGatt = bleDevice.connectGatt(getApplicationContext(), true, bluetoothGattCallback);
                        }
                    }
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    super.onBatchScanResults(results);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    super.onScanFailed(errorCode);
                    updateStatus("BLE scan failed");
                }
            };

    // Inline class that handles GATT connection callbacks.
    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status);
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyRead(gatt, txPhy, rxPhy, status);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            switch(newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    updateStatus("Connected to BP"+deviceId);
                    updateButton(Button_state.BUTT_DISCONNECT); // Change the text on the button to say disconnect.
                    bleConnected = true;

                    // We create file where data can be stored.
                    createFile();

                    // We start discovering services.
                    if(gatt.discoverServices()) {
                        updateStatus("Discovering Services");
                    }

                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    // Run these on the UI thread.
                    updateStatus("BLE Disconnected");
                    updateButton(Button_state.BUTT_CONNECT);    // Change the state of the button.
                    bleConnected = false;

                    // Close the file if it is open.
                    if(dataFile != null && ofStream != null) {
                        try {
                            ofStream.flush();
                            ofStream.close();
                            ofStream = null;
                            dataFile = null;
                        }
                        catch (IOException ex) {

                        }
                    }

                    break;
                default:
                    break;
            };
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            // Get a list of all the services.
            List<BluetoothGattService> services = gatt.getServices();
            for(BluetoothGattService service : services) {
                if(service.getUuid().toString().indexOf(serviceUUID) >= 0) {
                    biosenixService = service;
                    break;
                }
            }

            if(biosenixService != null) {
                // Get all the characteristics from this service.
                updateStatus("Retrieving Characteristics");
                List<BluetoothGattCharacteristic> characteristics = biosenixService.getCharacteristics();

                // Search for the characteristics we want to attach a notification handler to.
                for(BluetoothGattCharacteristic characteristic : characteristics) {
                    // Subscribe to notifications for ACC and PPG characteristics.
                    if(characteristic.getUuid().toString().indexOf(accCharUUID) >= 0) {
                        // Make sure it supports notification.
                        if((characteristic.getProperties() &
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY) ==
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
                            updateStatus("Notification supported");
                            gatt.setCharacteristicNotification(characteristic, true);
                            BluetoothGattDescriptor descriptor =
                                    characteristic.getDescriptor(UUID.fromString(notificationDesciptors));
                            // Some GATT servers uses indication while others use notifications.
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                            updateStatus("Notification subscribed");
                        }
                        else {
                            updateStatus("Notification Not Supported");
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            byte[] data = characteristic.getValue();
            if(data.length == 10) {
                // LSB comes first, then MSB
                int x = data[0] | (data[1] << 8);
                int y = data[2] | (data[3] << 8);
                int z = data[4] | (data[5] << 8);

                int timestamp = (data[6] & 0xFF) |
                        (data[7] & 0xFF) << 8 |
                        (data[8] & 0xFF) << 16 |
                        (data[9] & 0xFF) << 24;

                // Convert the timestamp into milliseconds.
                timestamp = (timestamp * 1000) / 1024;
                // This is the first record we saw, make all timestamps relative to this.
                if (startDataTimestamp < 0) {
                    startDataTimestamp = timestamp;
                }
                // Only write to the file if it is not a duplicate.
                if (timestamp > lastTimeStamp) {
                    timestamp = timestamp - startDataTimestamp;
                    // Write the output to a file and also update the UI thread to show this.
                    String line = String.format("%d,%d,%d,%d\n", timestamp, x, y, z);
                    // Also update the status field.
                    updateStatus(line);
                    try {
                        ofStream.write(line.getBytes(StandardCharsets.UTF_8));

                        // Also create an Acceleration object which will be passed to the Kinesis layer.
                        Acceleration acceleration = new Acceleration(deviceId, x, y, z, timestamp);
                        // Write the data onto the kinesis stream
                        kinesis.writeAccelToStream(acceleration);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    lastTimeStamp = timestamp;
                }
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
        }

        @Override
        public void onServiceChanged(@NonNull BluetoothGatt gatt) {
            super.onServiceChanged(gatt);
        }
    };
}