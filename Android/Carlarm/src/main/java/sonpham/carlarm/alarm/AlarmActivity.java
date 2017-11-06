package sonpham.carlarm.alarm;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import sonpham.carlarm.App;
import sonpham.carlarm.ui.Theme;
import trikita.anvil.RenderableView;
import trikita.jedux.Action;
import sonpham.carlarm.Actions;

import static trikita.anvil.DSL.FILL;
import static trikita.anvil.DSL.backgroundColor;
import static trikita.anvil.DSL.dip;
import static trikita.anvil.DSL.size;
import static trikita.anvil.DSL.text;
import static trikita.anvil.DSL.textColor;
import static trikita.anvil.DSL.textSize;


public class AlarmActivity extends Activity {
    private static final String TAG = AlarmActivity.class.getSimpleName();
    private static final UUID myUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String address = "98:D3:32:10:E9:E9";
    private PowerManager.WakeLock mWakeLock;

    BluetoothSocket btSocket = null;
    private boolean isBtConnected = false;

    Handler handler;
    ConnectedThread connectedThread;
    StringBuilder receivedMessage = new StringBuilder();

    ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "AlarmActivity");
        mWakeLock.acquire();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        // fill status bar with a theme dark color on post-Lollipop devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(Theme.get(App.getState().settings().theme()).primaryDarkColor);
        }

        setContentView(new RenderableView(this) {
            public void view() {
                Theme.materialIcon(() -> {
                    size(FILL, FILL);
                    text("\ue855"); // ALARM ON
                    textColor(Theme.get(App.getState().settings().theme()).accentColor);
                    textSize(dip(128));
                    backgroundColor(Theme.get(App.getState().settings().theme()).backgroundColor);
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
//                    onClick(v -> stopAlarm());
                });
            }
        });

        addControls();
    }

    private void addControls() {
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);

                switch (msg.what) {
                    case MessageConstants.MESSAGE_READ:
                        receivedMessage.append(new String((byte[]) msg.obj, 0, msg.arg1));
                        int eolIndex = receivedMessage.indexOf("\n");
                        if (eolIndex > 0) {
                            String message = receivedMessage.substring(0, eolIndex);
                            Toast.makeText(AlarmActivity.this, "Message read: " + message, Toast.LENGTH_SHORT).show();
                            if (message.equals("!OFF")) {
                                stopAlarm();
                            }
                            receivedMessage = new StringBuilder();
                        }
                        break;
                    case MessageConstants.MESSAGE_TOAST:
                        Toast.makeText(AlarmActivity.this, "Message toast: " + msg.getData().getString("toast"), Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        };
    }

    @Override
    protected void onUserLeaveHint() {
//        stopAlarm();
//        super.onUserLeaveHint();
    }

    @Override
    public void onBackPressed() {
//        stopAlarm();
//        super.onBackPressed();
    }


    @Override
    protected void onResume() {
        super.onResume();
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "onResume: Bluetooth is not supported");
            finish();
        }

        if (!bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.enable();
            BluetoothDevice btDevice = bluetoothAdapter.getRemoteDevice(address);
            new ConnectBT().execute(btDevice);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWakeLock.release();
        if (btSocket != null) {
            try {
                btSocket.getOutputStream().write("0".toString().getBytes());
            } catch (IOException e) {
                Log.e(TAG, "onDestroy: ", e);
            }
        }
    }

    private void stopAlarm() {
        App.dispatch(new Action<>(Actions.Alarm.DISMISS));
        finish();
    }

    private class ConnectBT extends AsyncTask<BluetoothDevice, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog = ProgressDialog.show(AlarmActivity.this, "Connecting", "Connecting to bluetooth device", true, false);
        }

        @Override
        protected Boolean doInBackground(BluetoothDevice... devices) //while the progress dialog is shown, the connection is done in background
        {
            try {
                if (btSocket == null || !isBtConnected) {
                    btSocket = devices[0].createInsecureRfcommSocketToServiceRecord(myUUID); // create a RFCOMM (SPP) connection
                    btSocket.connect(); // start connection
                }
            } catch (IOException e) {
                Log.e(TAG, "doInBackground: ", e);
                return Boolean.FALSE; // if the try failed, you can check the exception here
            }
            return Boolean.TRUE;
        }

        @Override
        protected void onPostExecute(Boolean result) //after the doInBackground, it checks if everything went fine
        {
            super.onPostExecute(result);
            progressDialog.dismiss();

            if (!result) {
                Toast.makeText(AlarmActivity.this, "Connection failed", Toast.LENGTH_SHORT).show();
                execute();
                finish();
            } else {
                isBtConnected = true;
                if (connectedThread == null) {
                    connectedThread = new ConnectedThread(btSocket);
                }
                connectedThread.write("1".getBytes());
                if (connectedThread.getState() == Thread.State.NEW || !connectedThread.isRunning || !connectedThread.isAlive()) {
                    Toast.makeText(AlarmActivity.this, "New thread started", Toast.LENGTH_SHORT).show();
                    connectedThread.start();
                }
            }
        }
    }

    // Defines several constants used when transmitting messages between the
    // service and the UI.
    private interface MessageConstants {
        int MESSAGE_READ = 0;
        int MESSAGE_WRITE = 1;
        int MESSAGE_TOAST = 2;
    }

    private class ConnectedThread extends Thread {
        boolean isRunning = false;
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer; // mmBuffer store for the stream

        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams; using temp objects because
            // member streams are final.
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            mmBuffer = new byte[1024];
            int numBytes; // bytes returned from read()
            isRunning = true;

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(mmBuffer);
                    // Send the obtained bytes to the UI activity.
                    Message readMsg = handler.obtainMessage(
                            MessageConstants.MESSAGE_READ, numBytes, -1, mmBuffer);
                    readMsg.sendToTarget();
                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }
            isRunning = false;
        }

        // Call this from the main activity to send data to the remote device.
        void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);

                // Share the sent message with the UI activity.
                Message writtenMsg = handler.obtainMessage(
                        MessageConstants.MESSAGE_WRITE, -1, -1, mmBuffer);
                writtenMsg.sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);

                // Send a failure message back to the activity.
                Message writeErrorMsg =
                        handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
                Bundle bundle = new Bundle();
                bundle.putString("toast",
                        "Couldn't send data to the other device");
                writeErrorMsg.setData(bundle);
                handler.sendMessage(writeErrorMsg);
            }
        }

        // Call this method from the main activity to shut down the connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }
    }

}
