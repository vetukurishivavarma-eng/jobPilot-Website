package com.jobpilot.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserJobRepository extends JpaRepository<UserJob, UUID> {
    String FILTER = " where uj.userId = :u and uj.status in :st and (:closed = true or j.closedAt is null)"
            + " and (lower(j.title) like :q or lower(j.company) like :q)";

    @Query(value = "select uj from UserJob uj join fetch uj.job j" + FILTER + " order by coalesce(j.postedAt, uj.discoveredAt) desc, j.id",
            countQuery = "select count(uj) from UserJob uj join uj.job j" + FILTER)
    Page<UserJob> search(UUID u, Collection<UserJob.Status> st, boolean closed, String q, Pageable page);

    /** What the job list needs to filter, score and sort a job: no description (the big column). */
    record Light(UUID linkId, UUID jobId, String title, String location, String workplace, Integer minYears, String seniority,
                 Long salaryMax, String salaryCurrency, UserJob.Origin origin) {}

    @Query("select new com.jobpilot.job.UserJobRepository$Light(uj.id, j.id, j.title, j.location, j.workplace, j.minYears, j.seniority,"
            + " j.salaryMax, j.salaryCurrency, uj.origin) from UserJob uj join uj.job j" + FILTER + " order by coalesce(j.postedAt, uj.discoveredAt) desc, j.id")
    java.util.List<Light> searchLight(UUID u, Collection<UserJob.Status> st, boolean closed, String q);

    @Query("select j.id, s from Job j join j.skills s where j.id in :ids")
    java.util.List<Object[]> skillsOf(Collection<UUID> ids);

    @Query("select uj from UserJob uj join fetch uj.job where uj.id in :ids")
    java.util.List<UserJob> withJobs(Collection<UUID> ids);

    /** Still-new, still-open jobs linked to the user after `since` (the email digest). */
    @Query("select uj.job from UserJob uj join uj.job j where uj.userId = :u and uj.discoveredAt > :since and uj.status = :st and j.closedAt is null")
    java.util.List<Job> newSince(UUID u, java.time.Instant since, UserJob.Status st);

    @Query("select uj from UserJob uj join fetch uj.job where uj.userId = :u and uj.job.id = :jobId")
    Optional<UserJob> find(UUID u, UUID jobId);

    @Query("select uj.job.id from UserJob uj where uj.userId = :u and uj.job.id in :ids")
    Set<UUID> linkedAmong(UUID u, Collection<UUID> ids);

    /** The user's still-open jobs (skills loaded lazily via @BatchSize; call inside a transaction). */
    @Query("select uj.job from UserJob uj join uj.job j where uj.userId = :u and j.closedAt is null")
    java.util.List<Job> openJobsOf(UUID u);
}
