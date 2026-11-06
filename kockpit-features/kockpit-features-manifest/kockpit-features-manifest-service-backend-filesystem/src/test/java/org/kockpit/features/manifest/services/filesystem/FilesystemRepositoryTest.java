package org.kockpit.features.manifest.services.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kockpit.features.manifest.services.dto.ManifestDto;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemRepositoryTest {

    @TempDir
    Path root;

    @Test
    void saves_and_reads_back_a_plain_file_name() {
        Path dir = root.resolve("manifests");
        FilesystemRepository repository = new FilesystemRepository(dir.toString());

        repository.save(ManifestDto.builder().domain("acme").env("dev").name("acme-dev.json").build());

        assertThat(dir.resolve("acme-dev.json")).exists();
        assertThat(repository.findByName("acme-dev.json")).hasValueSatisfying(m -> assertThat(m.getDomain()).isEqualTo("acme"));
    }

    @Test
    void refuses_names_that_leave_the_directory() throws Exception {
        Path dir = Files.createDirectories(root.resolve("manifests"));
        Files.writeString(root.resolve("outside.json"), "{\"domain\":\"x\",\"env\":\"y\"}");
        FilesystemRepository repository = new FilesystemRepository(dir.toString());

        for (String name : new String[]{"../evil.json", "sub/x.json", root.resolve("abs.json").toString(), "..", "."}) {
            assertThatThrownBy(() -> repository.save(ManifestDto.builder().domain("a").env("b").name(name).build()))
                    .as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(root.resolve("evil.json")).doesNotExist();
        assertThat(repository.findByName("../outside.json")).isEmpty();
    }
}
