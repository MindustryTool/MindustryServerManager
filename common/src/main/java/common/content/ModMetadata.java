package dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustry.mod.Mods.ModMeta;

@Data
@Accessors(chain = true)
public class ModMetaDto {
    private String name;
    private String internalName;
    private String minGameVersion = "0";
    private String displayName, author, description, subtitle, version, main, repo;
    private List<String> dependencies = new ArrayList<>();
    private boolean hidden;
    private boolean java;

    public static ModMetaDto from(ModMeta meta) {
        return new ModMetaDto()//
                .setAuthor(meta.author)//
                .setDependencies(meta.dependencies.list())
                .setDescription(meta.description)
                .setDisplayName(meta.displayName)
                .setHidden(meta.hidden)
                .setInternalName(meta.internalName)
                .setJava(meta.java)
                .setMain(meta.main)
                .setMinGameVersion(meta.minGameVersion)
                .setName(meta.name)
                .setRepo(meta.repo)
                .setSubtitle(meta.subtitle)
                .setVersion(meta.version);
    }
}
