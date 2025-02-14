package com.github.dschreid.groups.model.node;

import java.util.Objects;

public class PermissionNode implements Node {
    private final String permission;
    private boolean value;
    private long expiringDate;

    public PermissionNode(String permission) {
        this(permission, true, 0L);
    }

    public PermissionNode(String permission, boolean value) {
        this(permission, value, 0L);
    }

    public PermissionNode(String permission, boolean value, long expiringDate) {
        this.permission = permission;
        this.value = value;
        this.expiringDate = expiringDate;
    }

    @Override
    public String getPermission() {
        return permission;
    }

    @Override
    public boolean getValue() {
        return value;
    }

    @Override
    public void setValue(boolean value) {
        this.value = value;
    }

    @Override
    public long getExpiringDate() {
        return expiringDate;
    }

    public void setExpiringDate(long expiringDate) {
        this.expiringDate = expiringDate;
    }

    @Override
    public boolean isExpired() {
        if (this.expiringDate == 0) {
            return false;
        }
        return getTimeLeft() < 0;
    }

    @Override
    public long getTimeLeft() {
        if (expiringDate == 0) {
            return 0;
        }
        return this.expiringDate - System.currentTimeMillis();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionNode)) {
            return false;
        }
        PermissionNode that = (PermissionNode) o;
        return value == that.value
                && expiringDate == that.expiringDate
                && Objects.equals(permission, that.permission);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(permission, value, expiringDate);
    }
}
