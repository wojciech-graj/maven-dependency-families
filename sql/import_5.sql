CREATE INDEX idx_poms_version_id ON poms(version_id);

CREATE TABLE dependencies(
    from_version_id INTEGER NOT NULL REFERENCES versions(id),
    to_artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
    version TEXT,
    scope TEXT,
    managed BOOLEAN NOT NULL
);
