/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Exclave contributors                                   *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.vkturn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class VKTurnBean extends AbstractBean {

    public String vkLink;
    public boolean vlessMode;
    public boolean vlessBond;
    public int streams;
    public int streamsPerCred;
    public boolean udpToTurn;
    public boolean manualCaptcha;
    public boolean wrapEnabled;
    public String wrapKeyHex;
    public boolean debug;
    public String dnsMode;
    public String dnsServers;
    public String turnHost;
    public String turnPort;
    public long targetProfileId;

    @Override
    public void initializeDefaultValues() {
        if (serverAddress == null) serverAddress = "";
        if (serverPort == null || serverPort == 0) serverPort = 56000;
        super.initializeDefaultValues();
        if (vkLink == null) vkLink = "";
        if (streams <= 0) streams = 4;
        if (streamsPerCred <= 0) streamsPerCred = 10;
        if (wrapKeyHex == null) wrapKeyHex = "";
        if (dnsMode == null || dnsMode.isEmpty()) dnsMode = "auto";
        if (dnsServers == null) dnsServers = "";
        if (turnHost == null) turnHost = "";
        if (turnPort == null) turnPort = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(vkLink);
        output.writeBoolean(vlessMode);
        output.writeBoolean(vlessBond);
        output.writeInt(streams);
        output.writeInt(streamsPerCred);
        output.writeBoolean(udpToTurn);
        output.writeBoolean(manualCaptcha);
        output.writeBoolean(wrapEnabled);
        output.writeString(wrapKeyHex);
        output.writeBoolean(debug);
        output.writeString(dnsMode);
        output.writeString(dnsServers);
        output.writeString(turnHost);
        output.writeString(turnPort);
        output.writeLong(targetProfileId);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        vkLink = input.readString();
        vlessMode = input.readBoolean();
        vlessBond = input.readBoolean();
        streams = input.readInt();
        streamsPerCred = input.readInt();
        udpToTurn = input.readBoolean();
        manualCaptcha = input.readBoolean();
        wrapEnabled = input.readBoolean();
        wrapKeyHex = input.readString();
        debug = input.readBoolean();
        dnsMode = input.readString();
        dnsServers = input.readString();
        turnHost = input.readString();
        turnPort = input.readString();
        if (version >= 1) {
            targetProfileId = input.readLong();
        }
    }

    @Override
    public String network() {
        return vlessMode ? "tcp" : "udp";
    }

    @Override
    public boolean canMapping() {
        return false;
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof VKTurnBean bean)) return;
        bean.dnsMode = dnsMode;
        bean.dnsServers = dnsServers;
    }

    @NotNull
    @Override
    public VKTurnBean clone() {
        return KryoConverters.deserialize(new VKTurnBean(), KryoConverters.serialize(this));
    }

    public static final Creator<VKTurnBean> CREATOR = new CREATOR<VKTurnBean>() {
        @NonNull
        @Override
        public VKTurnBean newInstance() {
            return new VKTurnBean();
        }

        @Override
        public VKTurnBean[] newArray(int size) {
            return new VKTurnBean[size];
        }
    };
}
