/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2022 by Signalware Ltd <driver at aerialtv.eu>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

package info.martinmarinov.dvbservice;

import android.os.Build;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramSocket;

import info.martinmarinov.drivers.DvbDevice;

class UDPTransferThread extends TransferThread {
    private final DatagramSocket streamSocket;
    private final InetSocketAddress multicastAddr;

    UDPTransferThread(DvbDevice dvbDevice, DatagramSocket streamSocket, InetSocketAddress multicastAddr, OnClosedCallback callback) {
        super(dvbDevice, callback);
        this.streamSocket = streamSocket;
        this.multicastAddr = multicastAddr;
    }

    @Override
    public void interrupt() {
        super.interrupt();
        quietClose(streamSocket);

    }

    @Override
    public void run() {
        setName(TransferThread.class.getSimpleName());
        setPriority(NORM_PRIORITY);
        try {
            byte[] buf = new byte[5 * 188];

            transportStream = dvbDevice.getTransportStream(new DvbDevice.StreamCallback() {
                @Override
                public void onStreamException(IOException exception) {
                    lastException = exception;
                    interrupt();
                }

                @Override
                public void onStoppedStreaming() {
                    interrupt();
                }
            });
            while (!isInterrupted()) {
                int inlength = transportStream.read(buf);

                if (inlength > 0) {
                    DatagramPacket p = new DatagramPacket(buf, inlength, multicastAddr.getAddress(), multicastAddr.getPort());
                    streamSocket.send(p);
                } else {
                    // No data, sleep for a bit until available
                    quietSleep(10);
                }
            }
        } catch (IOException e) {
            lastException = e;
        } finally {
            quietClose(streamSocket);
            quietClose(transportStream);
            callback.onClosed();
        }
    }
}
class TCPTransferThread extends TransferThread {
    private final ServerSocket serverSocket;
    TCPTransferThread(DvbDevice dvbDevice, ServerSocket serverSocket, OnClosedCallback callback) {
        super(dvbDevice, callback);
        this.serverSocket = serverSocket;
    }

    @Override
    public void interrupt() {
        super.interrupt();
        quietClose(serverSocket);
    }

    @Override
    public void run() {
        setName(TransferThread.class.getSimpleName());
        setPriority(NORM_PRIORITY);

        OutputStream os = null;
        Socket socket = null;
        try {
            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            os = socket.getOutputStream();


            byte[] buf = new byte[5 * 188];


            transportStream = dvbDevice.getTransportStream(new DvbDevice.StreamCallback() {
                @Override
                public void onStreamException(IOException exception) {
                    lastException = exception;
                    interrupt();
                }

                @Override
                public void onStoppedStreaming() {
                    interrupt();
                }
            });
            while (!isInterrupted()) {
                int inlength = transportStream.read(buf);

                if (inlength > 0) {
                    os.write(buf, 0, inlength);
                } else {
                    // No data, sleep for a bit until available
                    quietSleep(10);
                }
            }
        } catch (IOException e) {
            lastException = e;
        } finally {
            quietClose(os);
            quietClose(socket);
            quietClose(transportStream);
            callback.onClosed();
        }
    }
}

abstract class TransferThread extends Thread {
    final DvbDevice dvbDevice;
    final OnClosedCallback callback;

    IOException lastException = null;
    InputStream transportStream;


    TransferThread(DvbDevice dvbDevice, OnClosedCallback callback) {
        this.dvbDevice = dvbDevice;
        this.callback = callback;
    }
    void quietSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            interrupt();
        }
    }

    void quietClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }
    void quietClose(DatagramSocket c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                try {
                    if (lastException == null) lastException = (IOException) e;
                } catch (Exception _) {

                }
            }
        }
    }
    void quietClose(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }

    void quietClose(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                if (lastException == null) lastException = e;
            }
        }
    }

    IOException signalAndWaitToDie() {
        if (isAlive()) {
            interrupt();
            try {
                join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return lastException;
    }

    interface OnClosedCallback {
        void onClosed();
    }
}
