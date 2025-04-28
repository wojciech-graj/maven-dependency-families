ALTER TABLE imported
    DROP COLUMN e,
    DROP COLUMN g,
    DROP COLUMN p,
    DROP COLUMN groupId,
    DROP COLUMN a,
    DROP COLUMN artifactId,
    DROP COLUMN v,
    DROP COLUMN version,
    DROP COLUMN l,
    DROP COLUMN del,
    DROP COLUMN descriptor,
    DROP COLUMN idxinfo;

DELETE FROM imported
WHERE u IS NULL;

ALTER TABLE imported
    ADD COLUMN group_id TEXT,
    ADD COLUMN artifact_id TEXT,
    ADD COLUMN version TEXT,
    ADD COLUMN classifier TEXT,
    ADD COLUMN ext TEXT,
    ADD COLUMN packaging TEXT,
    ADD COLUMN last_modified TEXT,
    ADD COLUMN size TEXT;

UPDATE
    imported
SET
    group_id = split_part(u, '|', 1),
    artifact_id = split_part(u, '|', 2),
    version = split_part(u, '|', 3),
    classifier = split_part(u, '|', 4),
    ext = split_part(u, '|', 5),
    packaging = split_part(i, '|', 1),
    last_modified = split_part(i, '|', 2),
    size = split_part(i, '|', 3);

ALTER TABLE imported
    DROP COLUMN u,
    DROP COLUMN i,
    ALTER COLUMN m TYPE TIMESTAMP
    USING to_timestamp(cast(m AS BIGINT) / 1000.0),
    ALTER COLUMN last_modified TYPE TIMESTAMP
        USING to_timestamp(cast(last_modified AS BIGINT) / 1000.0),
        ALTER COLUMN size TYPE BIGINT
            USING cast(size AS BIGINT);

CREATE TABLE artifacts(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    CONSTRAINT group_artifact_unqiue UNIQUE (group_id, artifact_id)
);

INSERT INTO artifacts(group_id, artifact_id)
SELECT
    group_id,
    artifact_id
FROM
    imported
ON CONFLICT (group_id,
    artifact_id)
    DO NOTHING;

CREATE TABLE versions(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    version TEXT NOT NULL,
    CONSTRAINT u_version_artifact UNIQUE (artifact_id, version)
);

INSERT INTO versions(artifact_id, version)
SELECT
    artifacts.id AS artifact_id,
    imported.version
FROM
    imported
    JOIN artifacts ON artifacts.group_id = imported.group_id
        AND artifacts.artifact_id = imported.artifact_id
    ON CONFLICT (artifact_id,
        version)
        DO NOTHING;

CREATE TABLE files(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id INTEGER NOT NULL REFERENCES versions(id),
    artifact_last_modified TIMESTAMP NOT NULL,
    file_last_modified TIMESTAMP NOT NULL,
    sha1 TEXT,
    bundle_symbolic_name TEXT,
    bundle_version TEXT,
    export_package TEXT,
    bundle_name TEXT,
    bundle_description TEXT,
    bundle_docurl TEXT,
    import_package TEXT,
    bundle_license TEXT,
    require_bundle TEXT,
    export_service TEXT,
    classifier TEXT,
    ext TEXT,
    packaging TEXT,
    size BIGINT
);

INSERT INTO files(version_id, artifact_last_modified, file_last_modified, sha1, bundle_symbolic_name, bundle_version, export_package, bundle_name, bundle_description, bundle_docurl, import_package, bundle_license, require_bundle, export_service, classifier, ext, packaging, size)
SELECT
    versions.id AS version_id,
    imported.m AS artifact_last_modified,
    imported.last_modified AS file_last_modified,
    imported._1 AS sha1,
    imported.bundle_symbolicname AS bundle_symbolic_name,
    imported.bundle_version,
    imported.export_package,
    imported.bundle_name,
    imported.bundle_description,
    imported.bundle_docurl,
    imported.import_package,
    imported.bundle_license,
    imported.require_bundle,
    imported.export_service,
    imported.classifier,
    imported.ext,
    imported.packaging,
    imported.size
FROM
    imported
    JOIN artifacts ON artifacts.group_id = imported.group_id
        AND artifacts.artifact_id = imported.artifact_id
    JOIN versions ON versions.artifact_id = artifacts.id
        AND versions.version = imported.version;

DROP TABLE imported;

CREATE INDEX idx_version_id ON files(version_id);

CREATE TABLE bundles(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bundle_symbolic_name TEXT NOT NULL UNIQUE
);

WITH split_data AS (
    SELECT
        unnest(string_to_array(bundle_symbolic_name, ',')) AS part
    FROM
        files
    WHERE
        bundle_symbolic_name IS NOT NULL
),
bundle_symbolic_names AS (
    SELECT
        trim(BOTH ' ' FROM split_part(part, ';', 1)) AS bundle_symbolic_name
    FROM
        split_data)
    INSERT INTO bundles(bundle_symbolic_name)
    SELECT
        bundle_symbolic_name
    FROM
        bundle_symbolic_names
    WHERE
        bundle_symbolic_name ~ '^[a-zA-Z0-9\-_\.]+$'
    ON CONFLICT (bundle_symbolic_name)
        DO NOTHING;

CREATE TABLE bundle_versions(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id INTEGER NOT NULL REFERENCES files(id),
    bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    bundle_version TEXT,
    export_package TEXT,
    import_package TEXT,
    require_bundle TEXT,
    export_service TEXT,
    CONSTRAINT bundle_bundle_version_unique UNIQUE (bundle_id, bundle_version)
);

WITH split_data AS (
    SELECT
        id,
        unnest(string_to_array(bundle_symbolic_name, ',')) AS part
    FROM
        files
    WHERE
        bundle_symbolic_name IS NOT NULL
        AND bundle_version ~ '^[a-zA-Z0-9\-_\.]+$'
),
bundle_symbolic_names AS (
    SELECT
        id,
        trim(BOTH ' ' FROM split_part(part, ';', 1)) AS bundle_symbolic_name
    FROM
        split_data)
    INSERT INTO bundle_versions(file_id, bundle_id, bundle_version, export_package, import_package, require_bundle, export_service)
    SELECT
        files.id AS file_id,
        bundles.id AS bundle_id,
        files.bundle_version,
        files.export_package,
        files.import_package,
        files.require_bundle,
        files.export_service
    FROM
        bundle_symbolic_names
        JOIN bundles ON bundles.bundle_symbolic_name = bundle_symbolic_names.bundle_symbolic_name
        JOIN files ON bundle_symbolic_names.id = files.id
    ON CONFLICT
        DO NOTHING;

ALTER TABLE files
    DROP COLUMN bundle_symbolic_name,
    DROP COLUMN bundle_version,
    DROP COLUMN export_package,
    DROP COLUMN bundle_name,
    DROP COLUMN bundle_description,
    DROP COLUMN bundle_docurl,
    DROP COLUMN import_package,
    DROP COLUMN bundle_license,
    DROP COLUMN require_bundle,
    DROP COLUMN export_service;

CREATE TABLE required_bundles(
    from_bundle_version_id INTEGER NOT NULL REFERENCES bundle_versions(id),
    to_bundle_id INTEGER NOT NULL REFERENCES bundles(id),
    version TEXT,
    PRIMARY KEY (from_bundle_version_id, to_bundle_id)
);

CREATE TABLE packages(
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE exported_packages(
    bundle_version_id INTEGER NOT NULL REFERENCES bundle_versions(id),
    package_id INTEGER NOT NULL REFERENCES packages(id),
    version TEXT NOT NULL DEFAULT '0.0.0',
    PRIMARY KEY (bundle_version_id, package_id, version)
);

CREATE TABLE imported_packages(
    from_bundle_version_id INTEGER NOT NULL REFERENCES bundle_versions(id),
    to_package_id INTEGER NOT NULL REFERENCES packages(id),
    version TEXT,
    PRIMARY KEY (from_bundle_version_id, to_package_id)
);
