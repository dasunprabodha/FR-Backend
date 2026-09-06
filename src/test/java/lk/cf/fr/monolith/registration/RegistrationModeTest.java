package lk.cf.fr.monolith.registration;

import lk.cf.fr.monolith.registration.model.RegistrationMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the wire contract for the registration-only capture mode: exact-case Auto/Manual accepted,
 * absent means Auto (backward compatibility for clients written before this field existed),
 * everything else rejected rather than coerced.
 */
class RegistrationModeTest {

    @Test
    void acceptsTheTwoExactWireValues() {
        assertThat(RegistrationMode.fromRequestValue("Auto")).isEqualTo(RegistrationMode.Auto);
        assertThat(RegistrationMode.fromRequestValue("Manual")).isEqualTo(RegistrationMode.Manual);
    }

    @Test
    void enumNameIsTheWireValue() {
        assertThat(RegistrationMode.Auto.name()).isEqualTo("Auto");
        assertThat(RegistrationMode.Manual.name()).isEqualTo("Manual");
    }

    @Test
    void missingOrBlankDefaultsToAutoSoOlderClientsKeepWorking() {
        assertThat(RegistrationMode.fromRequestValue(null)).isEqualTo(RegistrationMode.Auto);
        assertThat(RegistrationMode.fromRequestValue("")).isEqualTo(RegistrationMode.Auto);
        assertThat(RegistrationMode.fromRequestValue("   ")).isEqualTo(RegistrationMode.Auto);
    }

    @Test
    void wrongCaseIsRejectedRatherThanSilentlyCoerced() {
        for (String bad : new String[]{"auto", "AUTO", "manual", "MANUAL", "Random", "0"}) {
            assertThatThrownBy(() -> RegistrationMode.fromRequestValue(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mode")
                    .hasMessageContaining(bad);
        }
    }
}
