package com.jobpilot.profile;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "profiles")
public class Profile {

    /** Embeddable records double as API payload items, so validation lives here too. */
    @Embeddable
    public record Experience(@NotBlank @Size(max = 160) String company,
                             @NotBlank @Size(max = 160) String title,
                             @Size(max = 120) String location,
                             @NotNull LocalDate startDate,
                             LocalDate endDate,
                             @Size(max = 5000) String description) {}

    @Embeddable
    public record Education(@NotBlank @Size(max = 200) String institution,
                            @NotBlank @Size(max = 120) String degree,
                            @Size(max = 120) String field,
                            Integer startYear,
                            Integer endYear,
                            @Size(max = 40) String grade) {}

    @Id
    @Column(name = "user_id")
    UUID userId;
    String headline;
    String phone;
    String location;
    String currentTitle;
    BigDecimal yearsExperience;
    Long expectedSalary;
    String salaryCurrency;
    Integer noticePeriodDays;
    String workAuthorization;
    boolean willingToRelocate;
    String linkedinUrl;
    String githubUrl;
    String portfolioUrl;
    String summary;
    Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "profile_skills", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    @Column(name = "skill")
    List<String> skills = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "profile_desired_roles", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    @Column(name = "role")
    List<String> desiredRoles = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "profile_desired_locations", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    @Column(name = "location")
    List<String> desiredLocations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "profile_experiences", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    List<Experience> experiences = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "profile_educations", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    List<Education> educations = new ArrayList<>();

    protected Profile() {}

    Profile(UUID userId) { this.userId = userId; }
}
