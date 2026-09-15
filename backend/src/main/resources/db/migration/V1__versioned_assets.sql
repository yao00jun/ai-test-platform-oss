CREATE TABLE project (
  id VARCHAR(32) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description MEDIUMTEXT,
  version BIGINT NOT NULL DEFAULT 1,
  position INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT ck_project_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  parent_id VARCHAR(32),
  name VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  position INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_asset_project(id, project_id),
  KEY ix_asset_list(project_id, asset_type, deleted, position),
  KEY ix_asset_children(parent_id, deleted, position),
  CONSTRAINT fk_asset_project FOREIGN KEY(project_id) REFERENCES project(id),
  CONSTRAINT fk_asset_parent FOREIGN KEY(parent_id, project_id) REFERENCES asset(id, project_id),
  CONSTRAINT ck_asset_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_revision (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  operation VARCHAR(24) NOT NULL,
  source VARCHAR(16) NOT NULL,
  snapshot JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_revision(asset_id, version),
  KEY ix_revision_project(project_id, asset_id),
  CONSTRAINT fk_revision_project FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_relation (
  from_id VARCHAR(32) NOT NULL,
  to_id VARCHAR(32) NOT NULL,
  relation_type VARCHAR(64) NOT NULL,
  PRIMARY KEY(from_id,to_id,relation_type),
  KEY ix_relation_to(to_id),
  FOREIGN KEY(from_id) REFERENCES asset(id),
  FOREIGN KEY(to_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32),
  action VARCHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL,
  detail JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_audit_project(project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
