package io.github.yuroyami.kiteaudio.midi.coremidi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import io.github.yuroyami.kiteaudio.midi.devices.MidiPortException

/** The handful of CoreMIDI and CoreFoundation functions the backend needs, looked up once. */
internal object CoreMidi {
    private val linker: Linker = Linker.nativeLinker()
    private val arena: Arena = Arena.global()
    private val coreMidi: SymbolLookup = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/CoreMIDI", arena)
    private val coreFoundation: SymbolLookup = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena)
    private val libSystem: SymbolLookup = linker.defaultLookup()

    private fun handle(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("$name is missing from the framework") }, descriptor)

    // OSStatus MIDIClientCreate(CFStringRef name, MIDINotifyProc notifyProc, void *notifyRefCon, MIDIClientRef *outClient)
    val clientCreate: MethodHandle = handle(coreMidi, "MIDIClientCreate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    val clientDispose: MethodHandle = handle(coreMidi, "MIDIClientDispose", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    // OSStatus MIDIInputPortCreate(MIDIClientRef client, CFStringRef portName, MIDIReadProc readProc, void *refCon, MIDIPortRef *outPort)
    val inputPortCreate: MethodHandle = handle(coreMidi, "MIDIInputPortCreate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    val outputPortCreate: MethodHandle = handle(coreMidi, "MIDIOutputPortCreate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    val portDispose: MethodHandle = handle(coreMidi, "MIDIPortDispose", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    val portConnectSource: MethodHandle = handle(coreMidi, "MIDIPortConnectSource", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS))
    val portDisconnectSource: MethodHandle = handle(coreMidi, "MIDIPortDisconnectSource", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    val getNumberOfSources: MethodHandle = handle(coreMidi, "MIDIGetNumberOfSources", FunctionDescriptor.of(JAVA_LONG))
    val getSource: MethodHandle = handle(coreMidi, "MIDIGetSource", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    val getNumberOfDestinations: MethodHandle = handle(coreMidi, "MIDIGetNumberOfDestinations", FunctionDescriptor.of(JAVA_LONG))
    val getDestination: MethodHandle = handle(coreMidi, "MIDIGetDestination", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    // OSStatus MIDIObjectGetStringProperty(MIDIObjectRef obj, CFStringRef propertyID, CFStringRef *str)
    val objectGetStringProperty: MethodHandle = handle(coreMidi, "MIDIObjectGetStringProperty", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    val objectGetIntegerProperty: MethodHandle = handle(coreMidi, "MIDIObjectGetIntegerProperty", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    // OSStatus MIDISend(MIDIPortRef port, MIDIEndpointRef dest, const MIDIPacketList *pktlist)
    val send: MethodHandle = handle(coreMidi, "MIDISend", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS))
    val sourceCreate: MethodHandle = handle(coreMidi, "MIDISourceCreate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    val received: MethodHandle = handle(coreMidi, "MIDIReceived", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS))
    val endpointDispose: MethodHandle = handle(coreMidi, "MIDIEndpointDispose", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    val stringCreate: MethodHandle = handle(coreFoundation, "CFStringCreateWithCString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    val stringGetCString: MethodHandle = handle(coreFoundation, "CFStringGetCString", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))
    val release: MethodHandle = handle(coreFoundation, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS))
    // Notifications arrive on the run loop of the thread that created the client, so the backend runs one.
    val runLoopGetCurrent: MethodHandle = handle(coreFoundation, "CFRunLoopGetCurrent", FunctionDescriptor.of(ADDRESS))
    // SInt32 CFRunLoopRunInMode(CFStringRef mode, CFTimeInterval seconds, Boolean returnAfterSourceHandled)
    val runLoopRunInMode: MethodHandle = handle(coreFoundation, "CFRunLoopRunInMode", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_BYTE))
    val runLoopStop: MethodHandle = handle(coreFoundation, "CFRunLoopStop", FunctionDescriptor.ofVoid(ADDRESS))
    val retain: MethodHandle = handle(coreFoundation, "CFRetain", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val runLoopDefaultMode: MemorySegment =
        coreFoundation.find("kCFRunLoopDefaultMode").orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
    val timebaseInfo: MethodHandle = handle(libSystem, "mach_timebase_info", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    const val UTF8: Int = 0x08000100

    /**
     * The exported CFStringRef globals: the symbol holds a pointer to the string. The framework
     * fills them in when its first client is made, so they are read lazily, never at class init.
     */
    private fun global(name: String): MemorySegment =
        coreMidi.find(name).orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)

    val propertyName: MemorySegment by lazy { global("kMIDIPropertyName") }
    val propertyDisplayName: MemorySegment by lazy { global("kMIDIPropertyDisplayName") }
    val propertyManufacturer: MemorySegment by lazy { global("kMIDIPropertyManufacturer") }
    val propertyUniqueId: MemorySegment by lazy { global("kMIDIPropertyUniqueID") }
    val propertyDriverOwner: MemorySegment by lazy { global("kMIDIPropertyDriverOwner") }

    fun cfString(arena: Arena, text: String): MemorySegment =
        stringCreate.invoke(MemorySegment.NULL, arena.allocateFrom(text), UTF8) as MemorySegment

    inline fun <T> withCfString(arena: Arena, text: String, block: (MemorySegment) -> T): T {
        val name = cfString(arena, text)
        try { return block(name) } finally { if (name != MemorySegment.NULL) release.invoke(name) }
    }

    fun stringOf(ref: MemorySegment): String = Arena.ofConfined().use { scratch ->
        val buffer = scratch.allocate(1024)
        val ok = stringGetCString.invoke(ref, buffer, 1024L, UTF8) as Byte
        if (ok == 0.toByte()) "" else buffer.getString(0)
    }

    /** Upcall stub for MIDIReadProc(const MIDIPacketList *, void *readProcRefCon, void *srcConnRefCon). */
    fun readProcStub(target: Any, method: String, arena: Arena): MemorySegment {
        val type = MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java)
        val handle = MethodHandles.publicLookup().findVirtual(target::class.java, method, type).bindTo(target)
        return linker.upcallStub(handle, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), arena)
    }

    /** Upcall stub for MIDINotifyProc(const MIDINotification *, void *refCon). */
    fun notifyProcStub(target: Any, method: String, arena: Arena): MemorySegment {
        val type = MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java)
        val handle = MethodHandles.publicLookup().findVirtual(target::class.java, method, type).bindTo(target)
        return linker.upcallStub(handle, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), arena)
    }

    /**
     * MIDIPacketList { UInt32 numPackets; MIDIPacket packet[]; } and
     * MIDIPacket { MIDITimeStamp timeStamp; UInt16 length; Byte data[256]; }.
     * On Apple silicon MIDIPacket is packed to 4 bytes: timeStamp at 0, length at 8, data at 10,
     * and the next packet starts at data + length rounded up to 4. Intel Macs pack to 1.
     */
    fun forEachPacket(list: MemorySegment, onPacket: (timeStamp: Long, bytes: ByteArray) -> Unit) {
        val count = list.reinterpret(4).get(JAVA_INT, 0)
        var offset = 4L
        repeat(count) {
            val packet = list.reinterpret(offset + 10 + 65_536)
            val timeStamp = packet.get(JAVA_LONG.withByteAlignment(1), offset)
            val length = packet.get(JAVA_SHORT.withByteAlignment(1), offset + 8).toInt() and 0xFFFF
            val bytes = ByteArray(length) { i -> packet.get(JAVA_BYTE, offset + 10 + i) }
            onPacket(timeStamp, bytes)
            val next = 10L + length
            offset += if (ARM) (next + 3) and 3L.inv() else next
        }
    }

    /** One packet, timestamp zero (now), for sending. */
    fun packetList(arena: Arena, bytes: ByteArray): MemorySegment {
        if (bytes.size > 65_520) throw MidiPortException("a CoreMIDI send supports at most 65520 bytes; split large SysEx transfers")
        val list = arena.allocate((14L + bytes.size + 3L) and 3L.inv(), 4L)
        list.set(JAVA_INT, 0, 1)
        list.set(JAVA_LONG.withByteAlignment(1), 4, 0L)
        list.set(JAVA_SHORT.withByteAlignment(1), 12, bytes.size.toShort())
        bytes.forEachIndexed { i, b -> list.set(JAVA_BYTE, 14L + i, b) }
        return list
    }

    private val ARM: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
}
