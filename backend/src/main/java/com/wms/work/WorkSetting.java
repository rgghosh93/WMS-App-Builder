package com.wms.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How a kind of work is treated. */
@Entity
@Table(name = "work_settings")
public class WorkSetting {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WorkKind kind;

    /** Null for work that is as urgent as its orders. */
    private Integer priority;

    /** Whether people are offered it as their next task. */
    @Column(nullable = false)
    private boolean offered;

    protected WorkSetting() {
        // for JPA
    }

    public WorkKind getKind() { return kind; }
    public Integer getPriority() { return priority; }
    void setPriority(Integer priority) { this.priority = priority; }
    public boolean isOffered() { return offered; }
    void setOffered(boolean offered) { this.offered = offered; }
}
