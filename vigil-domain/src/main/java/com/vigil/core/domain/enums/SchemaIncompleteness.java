package com.vigil.core.domain.enums;

/** Why a schema blueprint may describe less than the target holds. */
public enum SchemaIncompleteness {
    /** The role lacked a privilege on a catalog, so nothing could be read. */
    CATALOG_PRIVILEGE_DENIED,
    /** A requested relation was not returned: it does not exist or is not visible to the role. */
    RELATION_NOT_VISIBLE
}
