package com.wms.users;

import java.util.Set;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What can be sent about users. PINs and passwords are only ever sent in, never out, and kept out of logs. */
public final class UserRequests {

    private UserRequests() {
    }

    /**
     * @param facilityIds where they work; everywhere if empty or left out
     * @param pin      for devices; leave out if they don't use any
     * @param password for the desktop; leave out if they don't use it. They choose their own the first time
     */
    public record Create(
            @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String userCode,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull ActiveStatus status,
            Set<UUID> roleIds,
            Set<UUID> facilityIds,
            String pin,
            String password) {
        @Override
        public String toString() {
            return "Create[userCode=" + userCode + "]";
        }
    }

    public record Update(
            @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String userCode,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull ActiveStatus status,
            Set<UUID> roleIds,
            Set<UUID> facilityIds,
            Long version) {
    }

    public record NewPin(String pin) {
        @Override
        public String toString() {
            return "NewPin[]";
        }
    }

    public record NewPassword(String password) {
        @Override
        public String toString() {
            return "NewPassword[]";
        }
    }

    public record RoleInput(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 250) String description,
            @NotNull Set<Permission> permissions,
            Long version) {
    }
}
