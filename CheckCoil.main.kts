import coil3.ComponentRegistry
import coil3.fetch.Fetcher
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.extensionReceiverParameter

fun main() {
    val clazz = ComponentRegistry.Builder::class
    println("Methods:")
    clazz.declaredMemberFunctions.forEach { 
        println(it.name + " -> " + it.parameters)
    }
}
