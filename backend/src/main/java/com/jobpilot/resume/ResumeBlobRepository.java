package com.jobpilot.resume;

import org.springframework.data.jpa.repository.JpaRepository;

interface ResumeBlobRepository extends JpaRepository<ResumeBlob, String> {}
