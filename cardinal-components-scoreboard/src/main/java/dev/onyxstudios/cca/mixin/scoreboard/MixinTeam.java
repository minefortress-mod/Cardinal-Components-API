/*
 * Cardinal-Components-API
 * Copyright (C) 2019-2023 Ladysnake
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.onyxstudios.cca.mixin.scoreboard;

import dev.onyxstudios.cca.api.v3.component.ComponentContainer;
import dev.onyxstudios.cca.api.v3.component.ComponentKey;
import dev.onyxstudios.cca.api.v3.component.ComponentProvider;
import dev.onyxstudios.cca.api.v3.component.sync.AutoSyncedComponent;
import dev.onyxstudios.cca.api.v3.component.sync.ComponentPacketWriter;
import dev.onyxstudios.cca.internal.scoreboard.CardinalComponentsScoreboard;
import dev.onyxstudios.cca.internal.scoreboard.StaticScoreboardComponentPlugin;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Mixin(Team.class)
public abstract class MixinTeam implements ComponentProvider, TeamAccessor {
    @Shadow
    public abstract String getName();

    @Shadow
    @Final
    private Scoreboard scoreboard;
    @Unique
    private ComponentContainer components;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initComponents(CallbackInfo ci) {
        this.components = StaticScoreboardComponentPlugin.teamComponentsContainerFactory.get().create(
            (Team) (Object) this,
            this.scoreboard,
            this.scoreboard instanceof ServerScoreboardAccessor acc ? acc.getServer() : null
        );
    }

    @Nonnull
    @Override
    public ComponentContainer getComponentContainer() {
        return this.components;
    }

    @Override
    public Iterable<ServerPlayerEntity> getRecipientsForComponentSync() {
        return this.scoreboard.asComponentProvider().getRecipientsForComponentSync();
    }

    @Nullable
    @Override
    public <C extends AutoSyncedComponent> CustomPayloadS2CPacket toComponentPacket(ComponentKey<? super C> key, ComponentPacketWriter writer, ServerPlayerEntity recipient) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(this.getName());
        buf.writeIdentifier(key.getId());
        writer.writeSyncPacket(buf, recipient);
        return (CustomPayloadS2CPacket) ServerPlayNetworking.createS2CPacket(CardinalComponentsScoreboard.TEAM_PACKET_ID, buf);
    }

}
