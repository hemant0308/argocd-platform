package com.argocd.platform.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled task execution.
 *
 * <p>Required for the deletion state machine scheduler
 * ({@link com.argocd.platform.api.task.DeletionStateTransitionTask}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
