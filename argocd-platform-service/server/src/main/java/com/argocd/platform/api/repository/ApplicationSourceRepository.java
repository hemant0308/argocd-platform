package com.argocd.platform.api.repository;

import com.argocd.platform.db.jooq.tables.pojos.ApplicationSourcesEntity;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.argocd.platform.db.jooq.Tables.APPLICATION_SOURCES;

@Repository
@RequiredArgsConstructor
public class ApplicationSourceRepository {

    private final DSLContext dsl;

    /**
     * Batch-inserts all source records for an application.
     * Sources are ordered by {@code sourceOrder} for multi-source applications.
     * No-op when {@code sources} is empty.
     */
    public void saveAll(List<ApplicationSourcesEntity> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        dsl.batch(
                sources.stream()
                        .map(s -> dsl.insertInto(APPLICATION_SOURCES)
                                .set(APPLICATION_SOURCES.APPLICATION_ID, s.getApplicationId())
                                .set(APPLICATION_SOURCES.REPO_URL, s.getRepoUrl())
                                .set(APPLICATION_SOURCES.REVISION, s.getRevision())
                                .set(APPLICATION_SOURCES.PATH, s.getPath())
                                .set(APPLICATION_SOURCES.CHART, s.getChart())
                                .set(APPLICATION_SOURCES.VALUES, s.getValues())
                                .set(APPLICATION_SOURCES.SOURCE_ORDER, s.getSourceOrder()))
                        .collect(Collectors.toList())
        ).execute();
    }

    /**
     * Removes all sources for the given application.
     * Called before re-inserting updated sources on PUT.
     */
    public void deleteByApplicationId(UUID applicationId) {
        dsl.deleteFrom(APPLICATION_SOURCES)
                .where(APPLICATION_SOURCES.APPLICATION_ID.eq(applicationId))
                .execute();
    }
}
