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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;

import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;

class DvbServer implements Closeable {
    private final static int SOCKET_TIMEOUT_MS = 20 * 1_000;
    private final ServerSocket controlSocket = new ServerSocket();
    private final ServerSocket transferSocket = new ServerSocket();
    private final DatagramSocket datagramSocket = new DatagramSocket();

    private final DvbDevice dvbDevice;
    private InetSocketAddress multicastAddr;

    DvbServer(DvbDevice dvbDevice) throws IOException {
        this.dvbDevice = dvbDevice;
    }

    DvbServerPorts bind(InetAddress address, String multicastAddr) throws IOException {
        return bind(new InetSocketAddress(address, 0), new InetSocketAddress(multicastAddr, 0));
    }

    private DvbServerPorts bind(InetSocketAddress address, InetSocketAddress multicastAddr) throws IOException {
        try {
            controlSocket.bind(address);
            transferSocket.bind(address);
            datagramSocket.setReuseAddress(true);
            controlSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            transferSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            this.multicastAddr = new InetSocketAddress(multicastAddr.getHostName(), transferSocket.getLocalPort());
            if (!controlSocket.getInetAddress().equals(transferSocket.getInetAddress()))
                throw new IllegalStateException();

            return new DvbServerPorts(controlSocket.getLocalPort(), transferSocket.getLocalPort(), multicastAddr.getHostName());
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    void open() throws DvbException {
        dvbDevice.open();
    }

    @Override
    public void close() {
        quietClose(dvbDevice);
        quietClose(controlSocket);
        quietClose(transferSocket);
        quietClose(datagramSocket);
    }

    void serve() throws IOException {
        DataInputStream inputStream = null;
        DataOutputStream outputStream = null;
        Socket control = null;
        try {
            control = controlSocket.accept();
            control.setTcpNoDelay(true);
            inputStream = new DataInputStream(control.getInputStream());
            outputStream = new DataOutputStream(control.getOutputStream());

            final InputStream finInputStream = inputStream;
//            TransferThread tcpWorker = new TCPTransferThread(dvbDevice, transferSocket, new TransferThread.OnClosedCallback() {
//                @Override
//                public void onClosed() {
//                    // Close input stream to cancel the request parsing
//                    quietClose(finInputStream);
//                }
//            });
            TransferThread udpWorker = new UDPTransferThread(dvbDevice, datagramSocket, multicastAddr, new TransferThread.OnClosedCallback() {
                @Override
                public void onClosed() {
                    // Close input stream to cancel the request parsing
                    quietClose(finInputStream);
                }
            });

            try {
//                tcpWorker.start();
                udpWorker.start();
                while (udpWorker.isAlive() && !Thread.currentThread().isInterrupted()) {
                    Request c = Request.parseAndExecute(inputStream, outputStream, dvbDevice);
                    if (c == Request.REQ_EXIT) break;
                }
            } catch (SocketException e) {
//                IOException workerException = tcpWorker.signalAndWaitToDie();
//                if (workerException != null) throw workerException;
                IOException workerException = udpWorker.signalAndWaitToDie();
                if (workerException != null) throw workerException;
                throw e;
            }

            // Finish successfully due to exit command, if worker failed for anything other
            // than a socket exception, throw it
//            IOException workerException = tcpWorker.signalAndWaitToDie();
//            if (workerException != null && !(workerException instanceof SocketException)) throw workerException;
            IOException workerException = udpWorker.signalAndWaitToDie();
            if (workerException != null && !(workerException instanceof SocketException)) throw workerException;
        } finally {
            quietClose(inputStream);
            quietClose(outputStream);
            quietClose(control);
        }
    }
    
    private static void quietClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void quietClose(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void quietClose(DatagramSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void quietClose(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
