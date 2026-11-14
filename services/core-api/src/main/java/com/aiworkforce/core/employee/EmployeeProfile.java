package com.aiworkforce.core.employee;

import com.aiworkforce.core.common.id.Ids;
import com.aiworkforce.core.workforce.EmploymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Professional record of a human employee in one organization. It may exist without a platform
 * membership, and a suspended or removed membership never changes it. It holds no credentials and
 * no identity-provider data.
 */
@Entity
@Table(name = "employee_profiles")
public class EmployeeProfile {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "membership_id")
    private UUID membershipId;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "professional_email")
    private String professionalEmail;

    @Column(name = "position_id")
    private UUID positionId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "manager_id")
    private UUID managerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmploymentStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected EmployeeProfile() {
    }

    EmployeeProfile(UUID organizationId, String displayName, LocalDate startDate, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.displayName = displayName;
        this.startDate = startDate;
        this.status = EmploymentStatus.ACTIVE;
        this.description = "";
        this.createdAt = now;
        this.updatedAt = now;
    }

    void updateDetails(String displayName, String employeeNumber, String professionalEmail, LocalDate startDate,
            String description, Instant now) {
        this.displayName = displayName;
        this.employeeNumber = employeeNumber;
        this.professionalEmail = professionalEmail;
        this.startDate = startDate;
        this.description = description;
        this.updatedAt = now;
    }

    void assignDepartment(UUID departmentId, Instant now) {
        this.departmentId = departmentId;
        this.updatedAt = now;
    }

    void assignPosition(UUID positionId, Instant now) {
        this.positionId = positionId;
        this.updatedAt = now;
    }

    void assignManager(UUID managerId, Instant now) {
        this.managerId = managerId;
        this.updatedAt = now;
    }

    void linkMembership(UUID membershipId, Instant now) {
        this.membershipId = membershipId;
        this.updatedAt = now;
    }

    void changeStatus(EmploymentStatus status, LocalDate endDate, Instant now) {
        this.status = status;
        this.endDate = endDate;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getMembershipId() { return membershipId; }
    public String getEmployeeNumber() { return employeeNumber; }
    public String getDisplayName() { return displayName; }
    public String getProfessionalEmail() { return professionalEmail; }
    public UUID getPositionId() { return positionId; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getManagerId() { return managerId; }
    public EmploymentStatus getStatus() { return status; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
