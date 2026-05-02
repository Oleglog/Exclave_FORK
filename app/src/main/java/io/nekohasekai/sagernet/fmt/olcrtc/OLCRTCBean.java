/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  olcRTC for Android contributors                        *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.olcrtc;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OLCRTCBean extends AbstractBean {

    public static final String PROVIDER_TELEMOST = "telemost";
    public static final String PROVIDER_JAZZ = "jazz";
    public static final String PROVIDER_WB_STREAM = "wb_stream";

    public String provider;
    public String roomId;
    public String keyHex;
    public String dnsServer;

    @Override
    public void initializeDefaultValues() {
        if (serverAddress == null || serverAddress.isEmpty()) serverAddress = "olcrtc";
        if (serverPort == null || serverPort == 0) serverPort = 1;
        super.initializeDefaultValues();
        if (provider == null || provider.isEmpty()) provider = PROVIDER_TELEMOST;
        if (roomId == null) roomId = "";
        if (keyHex == null) keyHex = "";
        if (dnsServer == null || dnsServer.isEmpty()) dnsServer = "1.1.1.1:53";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(provider);
        output.writeString(roomId);
        output.writeString(keyHex);
        output.writeString(dnsServer);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        provider = input.readString();
        roomId = input.readString();
        keyHex = input.readString();
        dnsServer = input.readString();
    }

    @Override
    public String network() {
        return "tcp,udp";
    }

    @Override
    public boolean canMapping() {
        // olcRTC always exposes a local SOCKS5 listener; the upstream
        // address that v2ray sees is 127.0.0.1, so domain mapping is
        // not meaningful here.
        return false;
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof OLCRTCBean bean)) return;
        bean.dnsServer = dnsServer;
    }

    @NotNull
    @Override
    public OLCRTCBean clone() {
        return KryoConverters.deserialize(new OLCRTCBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OLCRTCBean> CREATOR = new CREATOR<OLCRTCBean>() {
        @NonNull
        @Override
        public OLCRTCBean newInstance() {
            return new OLCRTCBean();
        }

        @Override
        public OLCRTCBean[] newArray(int size) {
            return new OLCRTCBean[size];
        }
    };
}
