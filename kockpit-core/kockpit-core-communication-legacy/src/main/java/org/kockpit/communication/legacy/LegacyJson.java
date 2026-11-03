package org.kockpit.communication.legacy;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de serialisation JSON des messages du systeme legacy.
 *
 * <p>Contrat impose par un systeme externe : ce mapper ne doit surtout pas etre le bean
 * {@code ObjectMapper} de Spring Boot, dont la configuration appartient a l'application hote.
 * Le format attendu est verifie par {@code FeatureFlippingLegacyMapperTest}.
 *
 * <p>Les reglages sont aujourd'hui identiques a ceux de {@code MessageJson}, mais les deux
 * contrats sont distincts : celui-ci suit un systeme tiers, l'autre suit Kockpit. Ils doivent
 * pouvoir evoluer separement.
 */
public final class LegacyJson {

    /**
     * Tolerance aux proprietes inconnues : les messages du systeme legacy portent des champs
     * que Kockpit n'a pas besoin de connaitre. {@code SORT_PROPERTIES_ALPHABETICALLY} est
     * desactive parce que Jackson 3 l'active par defaut, ce qui reordonnerait le JSON envoye
     * au systeme legacy.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private LegacyJson() {
    }

    /**
     * Mapper partage. En Jackson 3 un {@code ObjectMapper} est immuable et thread-safe :
     * une seule instance suffit pour tout le processus.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
