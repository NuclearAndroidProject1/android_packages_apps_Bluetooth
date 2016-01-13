/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import com.android.bluetooth.BluetoothObexTransport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.android.bluetooth.sdp.SdpManager;

/**
 * This class listens on OPUSH channel for incoming connection
 */
public class BluetoothOppL2capListener {
    private static final String TAG = "BluetoothOppL2capListener";

    private static final boolean V = Log.isLoggable(Constants.TAG, Log.VERBOSE);

    public static final int MSG_INCOMING_BTOPP_CONNECTION = 100;

    private volatile boolean mInterrupted;

    private Thread mSocketAcceptThread;

    private Handler mCallback;

    private static final int CREATE_RETRY_TIME = 10;

    private final BluetoothAdapter mAdapter;

    private BluetoothServerSocket mBtServerSocket = null;

    private ServerSocket mTcpServerSocket = null;

    private int mOppSdpHandle = -1;

    public BluetoothOppL2capListener(BluetoothAdapter adapter) {
        mAdapter = adapter;
    }


    public synchronized boolean start(Handler callback) {
        if (mSocketAcceptThread == null) {
            mCallback = callback;

            mSocketAcceptThread = new Thread(TAG) {

                public void run() {
                    if (Constants.USE_TCP_DEBUG) {
                        try {
                            if (V) Log.v(TAG, "Create TCP ServerSocket");
                            mTcpServerSocket = new ServerSocket(Constants.TCP_DEBUG_PORT, 1);
                        } catch (IOException e) {
                            Log.e(TAG, "Error listing on port" + Constants.TCP_DEBUG_PORT);
                            mInterrupted = true;
                        }
                        while (!mInterrupted) {
                            try {
                                Socket clientSocket = mTcpServerSocket.accept();

                                if (V) Log.v(TAG, "l2cap Socket connected!");
                                TestTcpTransport transport = new TestTcpTransport(clientSocket);
                                Message msg = Message.obtain();
                                msg.setTarget(mCallback);
                                msg.what = MSG_INCOMING_BTOPP_CONNECTION;
                                msg.obj = transport;
                                msg.sendToTarget();

                            } catch (IOException e) {
                                Log.e(TAG, "Error accept connection " + e);
                            }
                        }
                        if (V) Log.v(TAG, "TCP listen thread finished");
                    } else {
                        BluetoothSocket clientSocket;
                        while (!mInterrupted) {
                            try {
                                if (V) Log.v(TAG, "Accepting connection...");
                                if (mBtServerSocket == null) {

                                }
                                BluetoothServerSocket sSocket = mBtServerSocket;
                                if (sSocket ==null) {
                                    mInterrupted = true;

                                } else {
                                    clientSocket = sSocket.accept();
                                    Log.d(TAG, "Accepted connection from "
                                        + clientSocket.getRemoteDevice());
                                    BluetoothObexTransport transport = new BluetoothObexTransport(
                                        clientSocket);
                                    Message msg = Message.obtain();
                                    msg.setTarget(mCallback);
                                    msg.what = MSG_INCOMING_BTOPP_CONNECTION;
                                    msg.obj = transport;
                                    msg.sendToTarget();
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error accept connection " + e);
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ie) {
                                    Log.e(TAG, "mSocketAcceptThread was interrupted " + ie);
                                    mInterrupted = true;
                                }
                            }
                        }
                        Log.i(TAG, "BluetoothSocket listen thread finished");
                    }
                }
            };
            mInterrupted = false;
            if(!Constants.USE_TCP_SIMPLE_SERVER) {
                mSocketAcceptThread.start();
            }
        }
        return true;
    }

    public synchronized void stop() {
        if (mSocketAcceptThread != null) {
            Log.i(TAG, "stopping Accept Thread");

            mInterrupted = true;
             if (Constants.USE_TCP_DEBUG) {
                if (V) Log.v(TAG, "close mTcpServerSocket");
                if (mTcpServerSocket != null) {
                    try {
                        mTcpServerSocket.close();
                        mTcpServerSocket = null;
                    } catch (IOException e) {
                        Log.e(TAG, "Error close mTcpServerSocket");
                    }
                }
            } else {
                if (V) Log.v(TAG, "close mBtServerSocket");

                if (mBtServerSocket != null) {
                    try {
                        mBtServerSocket.close();
                        mBtServerSocket = null;
                    } catch (IOException e) {
                        Log.e(TAG, "Error close mBtServerSocket");
                    }
                }
            }
            if (mSocketAcceptThread != null) {
                if (V) Log.v(TAG, "Interrupting mSocketAcceptThread :" + mSocketAcceptThread);
                mSocketAcceptThread.interrupt();
            }
            mSocketAcceptThread = null;
            mCallback = null;
        }
    }

    public int getL2capChannel() {
            Log.d(TAG,"L2C channel is " +mBtServerSocket.getChannel());
            return mBtServerSocket.getChannel();
    }

    public BluetoothServerSocket openL2capSocket(){

        boolean serverOK = true;

        /*
         * it's possible that create will fail in some cases.
         * retry for 10 times
         */
         for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
             try {
                 if (V) Log.v(TAG, "Starting l2cap listener....");
                 mBtServerSocket = mAdapter.listenUsingInsecureL2capOn(BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP);
             } catch (IOException e1) {
                 Log.e(TAG, "Error create l2capServerSocket " + e1);
                 serverOK = false;
             }

             if (!serverOK) {
                // Need to break out of this loop if BT is being turned off.
                if (mAdapter == null) break;
                int state = mAdapter.getState();
                if ((state != BluetoothAdapter.STATE_TURNING_ON) &&
                    (state != BluetoothAdapter.STATE_ON)) {
                    Log.w(TAG, "L2cap listener failed as BT is (being) turned off");
                    break;
                }

                synchronized (this) {
                   try {
                       if (V) Log.v(TAG, "Wait 300 ms");
                       Thread.sleep(300);
                   } catch (InterruptedException e) {
                       Log.e(TAG, "socketAcceptThread thread was interrupted (3)");
                       mInterrupted = true;
                   }
                }
             } else {
                 break;
             }
         }
         if (!serverOK) {
             Log.e(TAG, "Error start listening after " + CREATE_RETRY_TIME + " try");
             mInterrupted = true;
         }
         if (!mInterrupted) {
             Log.i(TAG, "Accept thread started.");
         }

         return mBtServerSocket;
    }
}
