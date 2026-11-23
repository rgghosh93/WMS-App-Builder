package com.wms.outbound;

import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Where an order goes. */
@Embeddable
public record ShipTo(
        @Column(name = "ship_to_name") @NotBlank(message = "enter a name") @Size(max = 120) String name,
        @Column(name = "ship_to_line1") @NotBlank(message = "enter a street address") @Size(max = 120) String line1,
        @Column(name = "ship_to_line2") @Size(max = 120) String line2,
        @Column(name = "ship_to_city") @NotBlank(message = "enter a city") @Size(max = 80) String city,
        @Column(name = "ship_to_state") @Size(max = 40) String state,
        @Column(name = "ship_to_postal_code") @NotBlank(message = "enter a postal code") @Size(max = 20) String postalCode,
        @Column(name = "ship_to_country") @NotBlank(message = "enter a country")
        @Pattern(regexp = "^\\s*$|^\\s*[A-Za-z]{2}\\s*$", message = "use a 2-letter country code") String country) {

    ShipTo tidied() {
        return new ShipTo(name.trim(), line1.trim(), blankToNull(line2), city.trim(), blankToNull(state), postalCode.trim(),
                country.trim().toUpperCase(Locale.ROOT));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
