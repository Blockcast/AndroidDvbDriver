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

import java.io.Serializable;

public class DvbServerPorts implements Serializable {
    private final int controlPort;
    private final int transferPort;
    private String multicastAddr;

    public DvbServerPorts(int controlPort, int transferPort, String multicastAddr) {
        this.controlPort = controlPort;
        this.transferPort = transferPort;
        this.multicastAddr = multicastAddr;
    }

    public int getControlPort() {
        return controlPort;
    }

    public int getTransferPort() {
        return transferPort;
    }

    public String getMulticastAddr() {
        return multicastAddr;
    }
}