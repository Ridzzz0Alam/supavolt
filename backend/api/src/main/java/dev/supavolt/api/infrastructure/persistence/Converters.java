package dev.supavolt.api.infrastructure.persistence;

import dev.supavolt.contracts.Contracts.BucketAccess;
import dev.supavolt.contracts.Contracts.OrgRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JPA attribute converters for the control-plane columns that are not plain values. */
public final class Converters {

    private Converters() {
    }

    /** Encrypted at rest. A Spring bean, so Hibernate gets the protector injected. */
    @Component
    @Converter
    public static class Encrypted implements AttributeConverter<String, String> {

        private final SecretProtector protector;

        public Encrypted(SecretProtector protector) {
            this.protector = protector;
        }

        @Override
        public String convertToDatabaseColumn(String attribute) {
            return protector.protect(attribute);
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return protector.unprotect(dbData);
        }
    }

    /** {@code jsonb} array of strings. The column write is cast with {@code ?::jsonb} on the entity. */
    @Converter
    public static class StringList implements AttributeConverter<List<String>, String> {

        private static final JsonMapper JSON = JsonMapper.builder().build();
        private static final TypeReference<List<String>> TYPE = new TypeReference<>() { };

        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return JSON.writeValueAsString(attribute == null ? List.of() : attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            return dbData == null ? new ArrayList<>() : new ArrayList<>(JSON.readValue(dbData, TYPE));
        }
    }

    /** Stored as "Developer"/"Admin": the text the .NET API wrote, kept so existing rows still read. */
    @Converter
    public static class OrgRoleText implements AttributeConverter<OrgRole, String> {

        @Override
        public String convertToDatabaseColumn(OrgRole attribute) {
            return attribute == OrgRole.ADMIN ? "Admin" : "Developer";
        }

        @Override
        public OrgRole convertToEntityAttribute(String dbData) {
            return "Admin".equalsIgnoreCase(dbData) ? OrgRole.ADMIN : OrgRole.DEVELOPER;
        }
    }

    /** Stored as "Public"/"Private", for the same reason. */
    @Converter
    public static class BucketAccessText implements AttributeConverter<BucketAccess, String> {

        @Override
        public String convertToDatabaseColumn(BucketAccess attribute) {
            return attribute == BucketAccess.PRIVATE ? "Private" : "Public";
        }

        @Override
        public BucketAccess convertToEntityAttribute(String dbData) {
            return "Private".equalsIgnoreCase(dbData) ? BucketAccess.PRIVATE : BucketAccess.PUBLIC;
        }
    }
}
