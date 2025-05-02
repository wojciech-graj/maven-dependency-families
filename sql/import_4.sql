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
