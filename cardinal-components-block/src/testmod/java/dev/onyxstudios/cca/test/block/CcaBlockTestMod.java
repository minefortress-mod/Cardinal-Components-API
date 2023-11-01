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
package dev.onyxstudios.cca.test.block;

import dev.onyxstudios.cca.api.v3.block.BlockComponentFactoryRegistry;
import dev.onyxstudios.cca.api.v3.block.BlockComponentInitializer;
import dev.onyxstudios.cca.api.v3.block.BlockComponents;
import dev.onyxstudios.cca.test.base.LoadAwareTestComponent;
import dev.onyxstudios.cca.test.base.TickingTestComponent;
import dev.onyxstudios.cca.test.base.Vita;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

public class CcaBlockTestMod implements ModInitializer, BlockComponentInitializer {
    public static final String MOD_ID = "cca-block-test";
    public static final BlockApiLookup<Vita, Direction> VITA_API_LOOKUP = BlockApiLookup.get(new Identifier(MOD_ID, "sided_vita"), Vita.class, Direction.class);

    @Override
    public void registerBlockComponentFactories(BlockComponentFactoryRegistry registry) {
        registry.registerFor(EndGatewayBlockEntity.class, VitaCompound.KEY, VitaCompound::new);
        registry.registerFor(EndPortalBlockEntity.class, TickingTestComponent.KEY, be -> new TickingTestComponent());
        registry.registerFor(CommandBlockBlockEntity.class, LoadAwareTestComponent.KEY, be -> new LoadAwareTestComponent());
    }

    @Override
    public void onInitialize() {
        BlockComponents.exposeApi(Vita.KEY, VITA_API_LOOKUP, (vita, side) -> side == Direction.UP ? vita : null, BlockEntityType.END_PORTAL);
        BlockComponents.exposeApi(VitaCompound.KEY, VITA_API_LOOKUP, VitaCompound::get, BlockEntityType.END_GATEWAY);
    }
}
