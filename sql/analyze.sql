\copy (WITH cnts AS (
SELECT
    count(*) AS sz
FROM
    communities
GROUP BY
    community
)
SELECT
    sz,
    count(*)
FROM
    cnts
GROUP BY
    sz)
    TO 'family_sizes.csv' CSV HEADER;

\copy (WITH usage_counts AS (
SELECT
    to_artifact_id AS artifact_id,
    sum(cnt) AS cnt
FROM
    unmanaged_artifact_dependency_counts
WHERE
    from_artifact_id <> to_artifact_id
GROUP BY
    to_artifact_id),
community_counts AS (
    SELECT
        communities.community,
        usage_counts.cnt AS cnt
    FROM
        usage_counts
    RIGHT JOIN communities ON communities.artifact_id = usage_counts.artifact_id),
maxs AS (
    SELECT
        community,
        max(cnt) AS cnt
    FROM
        community_counts
    GROUP BY
        community),
    positions AS (
        SELECT
            community_counts.community,
            row_number() OVER (PARTITION BY community_counts.community ORDER BY coalesce(community_counts.cnt, 0) DESC) AS position,
            cast(community_counts.cnt AS REAL) / maxs.cnt AS usage
        FROM
            community_counts
            JOIN maxs ON maxs.community = community_counts.community),
        keep_communities AS (
            SELECT
                community
            FROM
                positions
            WHERE
                position = 1
                AND usage IS NOT NULL
)
        SELECT
            positions.position,
            coalesce(positions.usage, 0.0) AS usage
    FROM
        positions
        JOIN keep_communities ON keep_communities.community = positions.community
    ORDER BY
        positions.position ASC)
    TO 'use_freq.csv' CSV HEADER;

SELECT
    count(*) AS total_in_families
FROM
    communities;

SELECT
    count(*) AS total_not_in_families
FROM
    artifacts
    LEFT JOIN communities ON communities.artifact_id = artifacts.id
WHERE
    communities.community IS NULL;

\copy (WITH num_deps AS (
SELECT
    count(*) AS num_deps
FROM
    dependencies
    JOIN communities ON communities.artifact_id = dependencies.to_artifact_id
WHERE
    NOT dependencies.managed
GROUP BY
    dependencies.from_version_id,
    communities.community
)
SELECT
    count(*) AS cnt,
    num_deps
FROM
    num_deps
GROUP BY
    num_deps)
    TO 'co_use.csv' CSV HEADER;

\copy (WITH num_deps AS (
SELECT
    communities.community,
    count(*) AS num_deps
FROM
    dependencies
    JOIN communities ON communities.artifact_id = dependencies.to_artifact_id
WHERE
    NOT dependencies.managed
GROUP BY
    dependencies.from_version_id,
    communities.community),
community_sizes AS (
    SELECT
        community,
        count(*) AS sz
    FROM
        communities
    GROUP BY
        community
)
SELECT
    cast(num_deps AS REAL) / community_sizes.sz AS coeff
FROM
    num_deps
    JOIN community_sizes ON community_sizes.community = num_deps.community)
    TO 'norm_co_use.csv' CSV HEADER;

WITH counts AS (
    SELECT
        versions.artifact_id,
        count(DISTINCT versions.id) - 1 AS cnt
    FROM
        files
        JOIN versions ON versions.id = files.version_id
    WHERE
        files.classifier = 'sources'
        AND files.packaging = 'jar'
    GROUP BY
        sha1,
        versions.artifact_id
    HAVING
        count(DISTINCT versions.id) > 1
)
SELECT
    sum(counts.cnt) FILTER (WHERE communities.community IS NULL) AS empty_release_count_outside_families,
    sum(counts.cnt) FILTER (WHERE communities.community IS NOT NULL) AS empty_release_count_inside_families
FROM
    counts
    LEFT JOIN communities ON communities.artifact_id = counts.artifact_id;

SELECT
    count(*) FILTER (WHERE communities.community IS NULL) AS total_release_count_outside_families,
    count(*) FILTER (WHERE communities.community IS NOT NULL) AS total_release_count_inside_families
FROM
    versions
    LEFT JOIN communities ON communities.artifact_id = versions.artifact_id
WHERE
    EXISTS (
        SELECT
            *
        FROM
            files
        WHERE
            files.version_id = versions.id
            AND files.classifier = 'sources'
            AND files.packaging = 'jar');

\copy (WITH release_dates AS (
SELECT
    versions.id,
    max(files.artifact_last_modified) AS release_date
FROM
    versions
    JOIN communities ON communities.artifact_id = versions.artifact_id
    JOIN files ON files.version_id = versions.id
GROUP BY
    versions.id
)
SELECT
    versions.version,
    versions.artifact_id,
    release_dates.release_date,
    communities.community
FROM
    release_dates
    JOIN versions ON versions.id = release_dates.id
    JOIN communities ON communities.artifact_id = versions.artifact_id)
    TO 'releases.csv' CSV HEADER;

SELECT
    count(DISTINCT versions.artifact_id)
FROM
    bundle_versions
    JOIN files ON files.id = bundle_versions.file_id
    JOIN versions ON versions.id = files.version_id;

SELECT
    count(*)
FROM
    is_reproducible;

\copy (SELECT
size_diff
FROM (
    SELECT
        abs(lead(f.size) OVER (PARTITION BY v.artifact_id ORDER BY f.artifact_last_modified) - f.size) AS size_diff
    FROM
        files f
        JOIN versions v ON v.id = f.version_id
    WHERE
        f.classifier = 'sources'
        AND f.packaging = 'jar') sub
WHERE
    size_diff IS NOT NULL)
TO 'release_size_diffs.csv' CSV HEADER;

WITH diffs AS (
    SELECT
        v.artifact_id,
        lead(f.size) OVER (PARTITION BY v.artifact_id ORDER BY f.artifact_last_modified) - f.size AS size_diff
    FROM
        files f
        JOIN versions v ON v.id = f.version_id
    WHERE
        f.classifier = 'sources'
        AND f.packaging = 'jar'
),
filtered_diffs AS (
    SELECT
        d.artifact_id,
        d.size_diff
    FROM
        diffs d
    WHERE
        d.size_diff IS NOT NULL
        AND abs(d.size_diff) <= 4
)
SELECT
    count(*) FILTER (WHERE c.artifact_id IS NOT NULL) AS releases_inside_community_with_diff_leq4,
    count(*) FILTER (WHERE c.artifact_id IS NULL) AS releases_outside_community_with_diff_leq4
FROM
    filtered_diffs fd
    LEFT JOIN communities c ON c.artifact_id = fd.artifact_id;
