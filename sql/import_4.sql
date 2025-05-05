CREATE INDEX idx_version_range ON versions_in_range(version_range);

CREATE INDEX idx_version ON versions_in_range(version);

CREATE INDEX idx_imported_packages_version ON imported_packages(version);

CREATE INDEX idx_exported_packages_version ON exported_packages(version);

CREATE INDEX idx_artifact_id ON versions(artifact_id);

CREATE INDEX idx_version_id ON files(version_id);

CREATE INDEX idx_imported_packages_from_bundle_version_id ON imported_packages(from_bundle_version_id);

CREATE INDEX idx_exported_packages_bundle_version_id ON exported_packages(bundle_version_id);

CREATE INDEX idx_bundle_versions_bundle_id ON bundle_versions(bundle_id);

ALTER TABLE packages
    ADD COLUMN single BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN bundle_id INTEGER REFERENCES bundles(id);

UPDATE
    packages
SET
    single = TRUE
WHERE
    EXISTS (
        SELECT
            exported_packages.package_id
        FROM
            exported_packages
            JOIN bundle_versions ON bundle_versions.id = exported_packages.bundle_version_id
        WHERE
            exported_packages.package_id = packages.id
        GROUP BY
            exported_packages.package_id
        HAVING
            count(DISTINCT bundle_versions.bundle_id) <= 1);

UPDATE
    packages
SET
    bundle_id = bundle_versions.bundle_id
FROM
    exported_packages
    JOIN bundle_versions ON bundle_versions.id = exported_packages.bundle_version_id
WHERE
    exported_packages.package_id = packages.id
    AND packages.single;

ALTER TABLE packages
    DROP COLUMN single;

ALTER TABLE artifacts
    ADD COLUMN root_group_id TEXT;

UPDATE
    artifacts
SET
    root_group_id = val
FROM (
    SELECT
        a.id,
        coalesce(r1.match, r2.match) AS val
    FROM
        artifacts a
    LEFT JOIN LATERAL (
        SELECT
            (regexp_matches(a.group_id, '^([^\.]+\.[^\.]+)', ''))[1] AS match) r1 ON TRUE
        LEFT JOIN LATERAL (
            SELECT
                (regexp_matches(a.group_id, '^([^.]+)$', ''))[1] AS match) r2 ON TRUE) AS extracted
WHERE
    artifacts.id = extracted.id;

ALTER TABLE artifacts
    ALTER COLUMN root_group_id SET NOT NULL;

CREATE INDEX idx_root_group_id ON artifacts(root_group_id);

CREATE TABLE imported_bundles(
    from_bundle_version_id INTEGER NOT NULL,
    to_bundle_id INTEGER NOT NULL,
    PRIMARY KEY (from_bundle_version_id, to_bundle_id)
);

INSERT INTO imported_bundles(from_bundle_version_id, to_bundle_id)
SELECT
    imported_packages.from_bundle_version_id,
    bundle_versions.bundle_id AS to_bundle_id
FROM
    imported_packages
    JOIN packages ON packages.id = imported_packages.to_package_id
    JOIN exported_packages ON exported_packages.package_id = packages.id
    JOIN bundle_versions ON bundle_versions.id = exported_packages.bundle_version_id
    JOIN versions_in_range ON versions_in_range.version_range = imported_packages.version
        AND versions_in_range.version = exported_packages.version
WHERE
    packages.bundle_id IS NULL
ON CONFLICT
    DO NOTHING;

DROP TABLE versions_in_range;

INSERT INTO imported_bundles(from_bundle_version_id, to_bundle_id)
SELECT
    imported_packages.from_bundle_version_id,
    packages.bundle_id
FROM
    imported_packages
    JOIN packages ON packages.id = imported_packages.to_package_id
WHERE
    packages.bundle_id IS NOT NULL
    AND EXISTS (
        SELECT
            *
        FROM
            exported_packages
            JOIN versions_in_range ON versions_in_range.version = exported_packages.version
        WHERE
            exported_packages.package_id = packages.id
            AND imported_packages.version = versions_in_range.version_range)
ON CONFLICT
    DO NOTHING;

ALTER TABLE packages
    DROP COLUMN bundle_id;

INSERT INTO imported_bundles(from_bundle_version_id, to_bundle_id)
SELECT
    from_bundle_version_id,
    to_bundle_id
FROM
    required_bundles
ON CONFLICT
    DO NOTHING;

ALTER TABLE imported_bundles
    ADD CONSTRAINT fk_from_bundle_version_id FOREIGN KEY (from_bundle_version_id) REFERENCES bundle_versions(id),
    ADD CONSTRAINT fk_to_bundle_id FOREIGN KEY (to_bundle_id) REFERENCES bundles(id);

CREATE INDEX idx_imported_bundles_from_bundle_version_id ON imported_bundles(from_bundle_version_id);

CREATE INDEX idx_imported_bundles_to_bundle_id ON imported_bundles(to_bundle_id);

CREATE TABLE imported_bundle_counts(
    from_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    to_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    cnt INTEGER NOT NULL,
    PRIMARY KEY (from_bundle_id, to_bundle_id)
);

INSERT INTO imported_bundle_counts(from_bundle_id, to_bundle_id, cnt)
SELECT
    bundle_versions.bundle_id AS from_bundle_id,
    imported_bundles.to_bundle_id,
    count(*) AS cnt
FROM
    imported_bundles
    JOIN bundle_versions ON bundle_versions.id = imported_bundles.from_bundle_version_id
GROUP BY
    bundle_versions.bundle_id,
    imported_bundles.to_bundle_id;

CREATE INDEX idx_imported_bundle_counts_from_bundle_id ON imported_bundle_counts(from_bundle_id);

CREATE INDEX idx_imported_bundle_counts_to_bundle_id ON imported_bundle_counts(to_bundle_id);

CREATE TABLE mins(
    a_bundle_id INTEGER NOT NULL,
    b_bundle_id INTEGER NOT NULL,
    cnt INTEGER NOT NULL
);

INSERT INTO mins(a_bundle_id, b_bundle_id, cnt)
SELECT
    a.to_bundle_id AS a_bundle_id,
    b.to_bundle_id AS b_bundle_id,
    least(min(a.cnt), min(b.cnt)) AS cnt
FROM
    imported_bundle_counts AS a
    JOIN imported_bundle_counts AS b ON b.from_bundle_id = a.from_bundle_id
WHERE
    a.to_bundle_id < b.to_bundle_id
GROUP BY
    a.from_bundle_id,
    a.to_bundle_id,
    b.to_bundle_id;

ALTER TABLE mins
    ADD CONSTRAINT fk_a_bundle_id FOREIGN KEY (a_bundle_id) REFERENCES bundles(id),
    ADD CONSTRAINT fk_b_bundle_id FOREIGN KEY (b_bundle_id) REFERENCES bundles(id);

CREATE INDEX idx_mins_a_bundle_id ON mins(a_bundle_id);

CREATE INDEX idx_mins_b_bundle_id ON mins(b_bundle_id);

CREATE TABLE bundle_pair_sums(
    a_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    b_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    cnt INTEGER NOT NULL
);

INSERT INTO bundle_pair_sums(a_bundle_id, b_bundle_id, cnt)
SELECT
    mins.a_bundle_id,
    mins.b_bundle_id,
    sum(cnt) AS cnt
FROM
    mins
GROUP BY
    mins.a_bundle_id,
    mins.b_bundle_id;

DROP TABLE mins;

CREATE TABLE sums(
    bundle_id INTEGER NOT NULL PRIMARY KEY REFERENCES bundles(id),
    cnt INTEGER NOT NULL
);

INSERT INTO SUMS(bundle_id, cnt)
SELECT
    to_bundle_id AS bundle_id,
    cast(sum(cnt) AS REAL) AS cnt
FROM
    imported_bundle_counts
GROUP BY
    to_bundle_id;

CREATE TABLE bundle_overlap_coefficients(
    a_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    b_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    numerator INTEGER NOT NULL,
    denominator INTEGER NOT NULL,
    PRIMARY KEY (a_bundle_id, b_bundle_id)
);

INSERT INTO bundle_overlap_coefficients(a_bundle_id, b_bundle_id, numerator, denominator)
SELECT
    bundle_pair_sums.a_bundle_id,
    bundle_pair_sums.b_bundle_id,
    bundle_pair_sums.cnt AS numerator,
    least(sums_a.cnt, sums_b.cnt) AS denominator
FROM
    bundle_pair_sums
    JOIN sums AS sums_a ON sums_a.bundle_id = bundle_pair_sums.a_bundle_id
    JOIN sums AS sums_b ON sums_b.bundle_id = bundle_pair_sums.b_bundle_id;

DROP TABLE sums;

DROP TABLE bundle_pair_sums;

CREATE INDEX idx_bundle_overlap_coefficients_a_bundle_id ON bundle_overlap_coefficients(a_bundle_id);

CREATE INDEX idx_bundle_overlap_coefficients_b_bundle_id ON bundle_overlap_coefficients(b_bundle_id);

CREATE TABLE artifacts_for_bundles(
    artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    PRIMARY KEY (artifact_id, bundle_id)
);

INSERT INTO artifacts_for_bundles(artifact_id, bundle_id)
SELECT
    versions.artifact_id,
    bundle_versions.bundle_id
FROM
    bundle_versions
    JOIN files ON files.id = bundle_versions.file_id
    JOIN versions ON versions.id = files.version_id
ON CONFLICT
    DO NOTHING;

CREATE INDEX idx_artifacts_for_bundles_bundle_id ON artifacts_for_bundles(bundle_id);

CREATE INDEX idx_artifacts_for_bundles_artifact_id ON artifacts_for_bundles(artifact_id);

CREATE TABLE artifact_overlap_coefficients(
    a_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    b_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    coeff REAL NOT NULL,
    PRIMARY KEY (a_artifact_id, b_artifact_id)
);

INSERT INTO artifact_overlap_coefficients(a_artifact_id, b_artifact_id, coeff)
SELECT
    a_artifacts.id AS a_artifact_id,
    b_artifacts.id AS b_artifact_id,
    cast(sum(bundle_overlap_coefficients.numerator) AS REAL) / sum(bundle_overlap_coefficients.denominator)
FROM
    bundle_overlap_coefficients
    JOIN bundles AS a_bundles ON a_bundles.id = bundle_overlap_coefficients.a_bundle_id
    JOIN bundles AS b_bundles ON b_bundles.id = bundle_overlap_coefficients.b_bundle_id
    JOIN artifacts_for_bundles AS a_artifacts_for_bundles ON a_artifacts_for_bundles.bundle_id = a_bundles.id
    JOIN artifacts_for_bundles AS b_artifacts_for_bundles ON b_artifacts_for_bundles.bundle_id = b_bundles.id
    JOIN artifacts AS a_artifacts ON a_artifacts.id = a_artifacts_for_bundles.artifact_id
    JOIN artifacts AS b_artifacts ON b_artifacts.id = b_artifacts_for_bundles.artifact_id
WHERE
    a_artifacts.root_group_id = b_artifacts.root_group_id
GROUP BY
    a_artifacts.id,
    b_artifacts.id;

DROP TABLE artifacts_for_bundles;

DROP TABLE bundle_overlap_coefficients;

CREATE INDEX idx_artifact_overlap_coefficients_a_artifact_id ON artifact_overlap_coefficients(a_artifact_id);

CREATE INDEX idx_artifact_overlap_coefficients_b_artifact_id ON artifact_overlap_coefficients(b_artifact_id);

CREATE TABLE poms(
    version_id INTEGER NOT NULL PRIMARY KEY REFERENCES versions(id),
    value TEXT NOT NULL
);
