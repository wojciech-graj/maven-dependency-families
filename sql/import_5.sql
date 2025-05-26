CREATE INDEX idx_poms_version_id ON poms(version_id);

CREATE TABLE dependencies(
    from_version_id INTEGER NOT NULL REFERENCES versions(id),
    to_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    version TEXT,
    scope TEXT,
    managed BOOLEAN NOT NULL
);

CREATE TABLE parents(
    from_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    to_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    PRIMARY KEY (from_artifact_id, to_artifact_id)
);

CREATE TABLE is_reproducible(
    version_id INTEGER NOT NULL PRIMARY KEY REFERENCES versions(id)
);
