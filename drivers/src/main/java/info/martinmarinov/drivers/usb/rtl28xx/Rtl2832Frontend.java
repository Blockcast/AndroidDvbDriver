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

package info.martinmarinov.drivers.usb.rtl28xx;

import android.content.res.Resources;
import androidx.annotation.NonNull;
import android.util.Log;

import java.util.Set;

import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.R;
import info.martinmarinov.drivers.tools.Check;
import info.martinmarinov.drivers.tools.DvbMath;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.RegValue;
import info.martinmarinov.drivers.usb.rtl28xx.Rtl28xxDvbDevice.Rtl28xxI2cAdapter;

import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_TUNE_TO_FREQ;
import static info.martinmarinov.drivers.DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED;
import static info.martinmarinov.drivers.DvbException.ErrorCode.HARDWARE_EXCEPTION;
import static info.martinmarinov.drivers.DvbException.ErrorCode.UNSUPPORTED_BANDWIDTH;
import static info.martinmarinov.drivers.tools.I2cAdapter.I2cMessage.I2C_M_RD;
import static info.martinmarinov.drivers.usb.rtl28xx.Rtl2832FrontendData.DvbtRegBitName.DVBT_SOFT_RST;

class Rtl2832Frontend implements DvbFrontend {
    private final static String TAG = Rtl2832Frontend.class.getSimpleName();

    private final static int I2C_ADDRESS = 0x10;
    private final static long XTAL = 28_800_000L;

    private final Rtl28xxTunerType tunerType;
    private final Rtl28xxI2cAdapter i2cAdapter;
    private final Resources resources;

    private DvbTuner tuner;

    Rtl2832Frontend(Rtl28xxTunerType tunerType, Rtl28xxI2cAdapter i2cAdapter, Resources resources) {
        this.tunerType = tunerType;
        this.i2cAdapter = i2cAdapter;
        this.resources = resources;
    }

    @Override
    public DvbCapabilities getCapabilities() {
        return Rtl2832FrontendData.CAPABILITIES;
    }

    private void wr(int reg, byte[] val) throws DvbException {
        wr(reg, val, val.length);
    }

    private synchronized void wr(int reg, byte[] val, int length) throws DvbException {
        byte[] buf = new byte[length + 1];
        System.arraycopy(val, 0, buf, 1, length);
        buf[0] = (byte) reg;

        i2cAdapter.transfer(I2C_ADDRESS, 0, buf);
    }

    synchronized void wr(int reg, int page, byte[] val) throws DvbException {
        wr(reg, page, val, val.length);
    }

    private synchronized void wr(int reg, int page, int val) throws DvbException {
        wr(reg, page, new byte[] {(byte) val});
    }

    private synchronized void wr(int reg, int page, byte[] val, int length) throws DvbException {
        if (page != i2cAdapter.page) {
            wr(0x00, new byte[] {(byte) page});
            i2cAdapter.page = page;
        }
        wr(reg, val, length);
    }

    private synchronized void wrMask(int reg, int page, int mask, int val) throws DvbException {
        int orig = rd(reg, page);

        int tmp = (orig & ~mask) | (val & mask);

        wr(reg, page, new byte[] {(byte) tmp});
    }

    private static int calcBit(int val) {
        return 1 << val;
    }

    private static int calcRegMask(int val) {
        return calcBit(val + 1) - 1;
    }

    synchronized void wrDemodReg(DvbtRegBitName reg, long val) throws DvbException {
        int len = (reg.msb >> 3) + 1;
        byte[] reading = new byte[len];
        byte[] writing = new byte[len];

        int mask = calcRegMask(reg.msb - reg.lsb);

        rd(reg.startAddress, reg.page, reading);

        int readingTmp = 0;
        for (int i = 0; i < len; i++) {
            readingTmp |= (reading[i] & 0xFF) << ((len - 1 - i) * 8);
        }

        int writingTmp = readingTmp & ~(mask << reg.lsb);
        writingTmp |= ((val & mask) << reg.lsb);

        for (int i = 0; i < len; i++) {
            writing[i] = (byte) (writingTmp >> ((len - 1 - i) * 8));
        }

        wr(reg.startAddress, reg.page, writing);
    }

    private synchronized void wrDemodRegs(RegValue[] values) throws DvbException {
        for (RegValue regValue : values) wrDemodReg(regValue.reg, regValue.val);
    }

    private synchronized void rd(int reg, byte[] val) throws DvbException {
        i2cAdapter.transfer(
                I2C_ADDRESS, 0, new byte[] {(byte) reg},
                I2C_ADDRESS, I2C_M_RD, val
        );
    }

    private synchronized void rd(int reg, int page, byte[] val) throws DvbException {
        if (page != i2cAdapter.page) {
            wr(0x00, new byte[] {(byte) page});
            i2cAdapter.page = page;
        }
        rd(reg, val);
    }

    private synchronized int rd(int reg, int page) throws DvbException {
        byte[] result = new byte[1];
        rd(reg, page, result);
        return result[0] & 0xFF;
    }

    private synchronized long rdDemodReg(DvbtRegBitName reg) throws DvbException {
        int len = (reg.msb >> 3) + 1;
        byte[] reading = new byte[len];

        int mask = calcRegMask(reg.msb - reg.lsb);

        rd(reg.startAddress, reg.page, reading);

        long readingTmp = 0;
        for (int i = 0; i < len; i++) {
            readingTmp |= ((long) (reading[i] & 0xFF)) << ((len - 1 - i) * 8);
        }

        return (readingTmp >> reg.lsb) & mask;
    }

    private void setIf(long if_freq) throws DvbException {
        int en_bbin = (if_freq == 0 ? 0x1 : 0x0);

	    /*
	     * PSET_IFFREQ = - floor((IfFreqHz % CrystalFreqHz) * pow(2, 22)
	     *		/ CrystalFreqHz)
	    */

        long pset_iffreq = if_freq % XTAL;
        pset_iffreq *= 0x400000;
        pset_iffreq = DvbMath.divU64(pset_iffreq, XTAL);
        pset_iffreq = -pset_iffreq;
        pset_iffreq = pset_iffreq & 0x3fffff;

        wrDemodReg(DvbtRegBitName.DVBT_EN_BBIN, en_bbin);
        wrDemodReg(DvbtRegBitName.DVBT_PSET_IFFREQ, pset_iffreq);
    }

    @Override
    public synchronized void attatch() throws DvbException {
        /* check if the demod is there */
        rd(0, 0);
        wrDemodReg(DVBT_SOFT_RST, 0x1);
    }

    @Override
    public synchronized void release() {
        try {
            wrDemodReg(DVBT_SOFT_RST, 0x1);
        } catch (DvbException e) {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;

        unsetSdrMode();

        wrDemodReg(DVBT_SOFT_RST, 0x0);
        wrDemodRegs(Rtl2832FrontendData.INITIAL_REGS);

        switch (tunerType) {
            case RTL2832_E4000:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_E4000);
                break;
            case RTL2832_R820T:
            case RTL2832_R828D:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_R820T);
                break;
            case RTL2832_FC0012:
            case RTL2832_FC0013:
                wrDemodRegs(Rtl2832FrontendData.TUNER_INIT_FC0012);
                break;
            default:
                throw new DvbException(DVB_DEVICE_UNSUPPORTED, resources.getString(R.string.unsupported_tuner_on_device));
        }

        // Skipping IF since no tuners support IF from what I can see

        /*
	    * r820t NIM code does a software reset here at the demod -
	    * may not be needed, as there's already a software reset at set_params()
	    */
        wrDemodReg(DVBT_SOFT_RST, 0x1);
        wrDemodReg(DVBT_SOFT_RST, 0x0);

        tuner.init();
    }

    private void unsetSdrMode() throws DvbException {
        /* PID filter */
        wr(0x61, 0, 0xe0);
	    /* mode */
        wr(0x19, 0, 0x20);
        wr(0x17, 0, new byte[] {(byte) 0x11, (byte) 0x10});
	    /* FSM */
        wr(0x92, 1, new byte[] {(byte) 0x00, (byte) 0x0f, (byte) 0xff});
        wr(0x3e, 1, new byte[] {(byte) 0x40, (byte) 0x00});
        wr(0x15, 1, new byte[] {(byte) 0x06, (byte) 0x3f, (byte) 0xce, (byte) 0xcc});
    }

    @Override
    public synchronized void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        Check.notNull(tuner);
        if (deliverySystem != DeliverySystem.DVBT) throw new DvbException(CANNOT_TUNE_TO_FREQ, resources.getString(R.string.unsupported_delivery_system));

        tuner.setParams(frequency, bandwidthHz, deliverySystem);
        setIf(tuner.getIfFrequency());

        int i;
        long bwMode;
        switch ((int) bandwidthHz) {
            case 6000000:
                i = 0;
                bwMode = 48000000;
                break;
            case 7000000:
                i = 1;
                bwMode = 56000000;
                break;
            case 8000000:
                i = 2;
                bwMode = 64000000;
                break;
            default:
                throw new DvbException(UNSUPPORTED_BANDWIDTH, resources.getString(R.string.invalid_bw));
        }

        byte[] byteToSend = new byte[1];
        for (int j = 0; j < Rtl2832FrontendData.BW_PARAMS[0].length; j++) {
            byteToSend[0] = Rtl2832FrontendData.BW_PARAMS[i][j];
            wr(0x1c+j, 1, byteToSend);
        }

        /* calculate and set resample ratio
	    * RSAMP_RATIO = floor(CrystalFreqHz * 7 * pow(2, 22)
	    *	/ ConstWithBandwidthMode)
	    */
        long num = XTAL * 7;
        num *= 0x400000L;
        num = DvbMath.divU64(num, bwMode);
        long resampRatio =  num & 0x3ffffff;
        wrDemodReg(DvbtRegBitName.DVBT_RSAMP_RATIO, resampRatio);

	    /* calculate and set cfreq off ratio
	     * CFREQ_OFF_RATIO = - floor(ConstWithBandwidthMode * pow(2, 20)
	     *	/ (CrystalFreqHz * 7))
	    */
        num = bwMode << 20;
        long num2 = XTAL * 7;
        num = DvbMath.divU64(num, num2);
        num = -num;
        long cfreqOffRatio = num & 0xfffff;
        wrDemodReg(DvbtRegBitName.DVBT_CFREQ_OFF_RATIO, cfreqOffRatio);

	    /* soft reset */
        wrDemodReg(DVBT_SOFT_RST, 0x1);
        wrDemodReg(DVBT_SOFT_RST, 0x0);
    }

    @Override
    public synchronized int readSnr() throws DvbException {
	    /* reports SNR in resolution of 0.1 dB */
        int tmp = rd(0x3c, 3);

        int constellation = (tmp >> 2) & 0x03; /* [3:2] */
        if (constellation >= Rtl2832FrontendData.CONSTELLATION_NUM) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_read_snr));
        int hierarchy = (tmp >> 4) & 0x07; /* [6:4] */
        if (hierarchy >= Rtl2832FrontendData.HIERARCHY_NUM) throw new DvbException(HARDWARE_EXCEPTION, resources.getString(R.string.cannot_read_snr));

        byte[] buf = new byte[2];
        rd(0x0c, 4, buf);

        int tmp16 = (buf[0] & 0xFF) << 8 | (buf[1] & 0xFF);
        if (tmp16 == 0) return 0;
        return (Rtl2832FrontendData.SNR_CONSTANTS[constellation][hierarchy] - DvbMath.intlog10(tmp16)) / ((1 << 24) / 100);
    }

    @Override
    public synchronized int readRfStrengthPercentage() throws DvbException {
        long tmp = rdDemodReg(DvbtRegBitName.DVBT_FSM_STAGE);
        if (tmp == 10 || tmp == 11) {
            // If it has signal
            int u8tmp = rd(0x05, 3);

            u8tmp = (~u8tmp) & 0xFF;
            int strength = u8tmp << 8 | u8tmp;
            return (100 * strength) / 0xffff;
        } else {
            return 0;
        }
    }

    @Override
    public synchronized int readBer() throws DvbException {
        byte[] buf = new byte[2];
        rd(0x4e, 3, buf);
        // Default unit is bit error per 1MB
        return (buf[0] & 0xFF) << 8 | (buf[1] & 0xFF);
    }

    @Override
    public synchronized Set<DvbStatus> getStatus() throws DvbException {
        long tmp = rdDemodReg(DvbtRegBitName.DVBT_FSM_STAGE);
        if (tmp == 11) {
            return SetUtils.setOf(DvbStatus.FE_HAS_SIGNAL, DvbStatus.FE_HAS_CARRIER,
                    DvbStatus.FE_HAS_VITERBI, DvbStatus.FE_HAS_SYNC, DvbStatus.FE_HAS_LOCK);
        } else if (tmp == 10) {
            return SetUtils.setOf(DvbStatus.FE_HAS_SIGNAL, DvbStatus.FE_HAS_CARRIER,
                    DvbStatus.FE_HAS_VITERBI);
        }
        return SetUtils.setOf();
    }

    @Override
    public synchronized void setPids(int... pids) throws DvbException {
        setPids(false, pids);
    }

    @Override
    public synchronized void disablePidFilter() throws DvbException {
        disablePidFilter(false);
    }

    void setPids(boolean slaveTs, int ... pids) throws DvbException {
        if (!hardwareSupportsPidFilterOf(pids)) {
            // if can't do hardware filtering, fallback to software
            Log.d(TAG, "Falling back to software PID filtering");
            disablePidFilter(slaveTs);
            return;
        }

        enablePidFilter(slaveTs);

        long pidFilter = 0;
        for (int index = 0; index < pids.length; index++) {
            pidFilter |= 1 << index;
        }

        // write mask
        byte[] buf = new byte[] {
                (byte) (pidFilter & 0xFF),
                (byte) ((pidFilter >> 8) & 0xFF),
                (byte) ((pidFilter >> 16) & 0xFF),
                (byte) ((pidFilter >> 24) & 0xFF)
        };

        if (slaveTs) {
            wr(0x22, 0, buf);
        } else {
            wr(0x62, 0, buf);
        }

        for (int index = 0; index < pids.length; index++) {
            int pid = pids[index];

            buf[0] = (byte) ((pid >> 8) & 0xFF);
            buf[1] = (byte) (pid & 0xFF);

            if (slaveTs) {
                wr(0x26 + 2 * index, 0, buf, 2);
            } else {
                wr(0x66 + 2 * index, 0, buf, 2);
            }
        }
    }

    void disablePidFilter(boolean slaveTs) throws DvbException {
        if (slaveTs) {
            wrMask(0x21, 0, 0xc0, 0xc0);
        } else {
            wrMask(0x61, 0, 0xc0, 0xc0);
        }
    }

    private void enablePidFilter(boolean slaveTs) throws DvbException {
        if (slaveTs) {
            wrMask(0x21, 0, 0xc0, 0x80);
        } else {
            wrMask(0x61, 0, 0xc0, 0x80);
        }
    }

    private static boolean hardwareSupportsPidFilterOf(int ... pids) {
        if (pids.length > 32) {
            return false;
        }

        // Avoid unnecessary unpacking, ignore Android Studio warning
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < pids.length; i++) {
            if (pids[i] < 0 || pids[i] > 0x1FFF) return false;
        }

        return true;
    }
}
