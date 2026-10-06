package app.bililisten.shared

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import java.io.StringReader

internal actual fun readLyricsYaml(text:String):Map<*,*> {
    val options=LoaderOptions().apply {
        isAllowDuplicateKeys=false
        maxAliasesForCollections=0
        allowRecursiveKeys=false
        codePointLimit=512000
        nestingDepthLimit=20
    }
    val yaml=Yaml(SafeConstructor(options))
    var count=0
    for(event in yaml.parse(StringReader(text))) {
        require(event !is AliasEvent && ++count<=240000)
    }
    return yaml.load<Any>(text) as? Map<*,*> ?: error("Invalid lyrics document")
}
