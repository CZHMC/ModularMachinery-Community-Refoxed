package cn.howxu.mmcr.internal.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the blueprint stage update payload boundary.
 * @author howxu <dev@howxu.cn>
 */
class PktBlueprintStageUpdatePayloadTest {
    @Test
    void payload_round_trips_stage_number() {
        PktBlueprintStageUpdatePayload payload = new PktBlueprintStageUpdatePayload(3);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY,
                ConnectionType.NEOFORGE);

        PktBlueprintStageUpdatePayload.STREAM_CODEC.encode(buffer, payload);

        assertThat(PktBlueprintStageUpdatePayload.STREAM_CODEC.decode(buffer)).isEqualTo(payload);
        buffer.release();
    }

    @Test
    void payload_rejects_non_positive_stage_numbers() {
        assertThatThrownBy(() -> new PktBlueprintStageUpdatePayload(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PktBlueprintStageUpdatePayload(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
