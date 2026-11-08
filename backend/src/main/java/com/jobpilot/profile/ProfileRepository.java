package com.jobpilot.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    @Query(value = "select role from profile_desired_roles where user_id = :userId order by position", nativeQuery = true)
    List<String> desiredRoles(UUID userId);

    @Query(value = "select location from profile_desired_locations where user_id = :userId order by position", nativeQuery = true)
    List<String> desiredLocations(UUID userId);
}
