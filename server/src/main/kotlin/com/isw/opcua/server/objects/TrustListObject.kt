package com.isw.opcua.server.objects

import com.isw.opcua.server.util.ExecutableByAdmin
import org.bouncycastle.util.encoders.Hex
import org.eclipse.milo.opcua.sdk.server.api.MethodInvocationHandler
import org.eclipse.milo.opcua.sdk.server.model.methods.AddCertificateMethod
import org.eclipse.milo.opcua.sdk.server.model.methods.CloseAndUpdateMethod
import org.eclipse.milo.opcua.sdk.server.model.methods.OpenWithMasksMethod
import org.eclipse.milo.opcua.sdk.server.model.methods.RemoveCertificateMethod
import org.eclipse.milo.opcua.sdk.server.model.nodes.objects.TrustListNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.UaException
import org.eclipse.milo.opcua.stack.core.security.TrustListManager
import org.eclipse.milo.opcua.stack.core.serialization.EncodingLimits
import org.eclipse.milo.opcua.stack.core.types.OpcUaDataTypeManager
import org.eclipse.milo.opcua.stack.core.types.OpcUaDefaultBinaryEncoding
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.TrustListMasks
import org.eclipse.milo.opcua.stack.core.types.structured.TrustListDataType
import org.eclipse.milo.opcua.stack.core.util.CertificateUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.util.concurrent.atomic.AtomicReference


private val logger: Logger = LoggerFactory.getLogger(TrustListObject::class.java)

class TrustListObject(
    private val trustListNode: TrustListNode,
    private val trustListManager: TrustListManager
) : FileObject(trustListNode, { trustListManager.openTrustListFile() }) {

    override fun onStartup() {
        super.onStartup()

        trustListNode.openWithMasksMethodNode.apply {
            invocationHandler = OpenWithMasksImpl(this)
            setAttributeDelegate(ExecutableByAdmin)
        }

        trustListNode.closeAndUpdateMethodNode.apply {
            invocationHandler = CloseAndUpdateImpl(this)
            setAttributeDelegate(ExecutableByAdmin)
        }

        trustListNode.addCertificateMethodNode.apply {
            invocationHandler = AddCertificateImpl(this)
            setAttributeDelegate(ExecutableByAdmin)
        }

        trustListNode.removeCertificateMethodNode.apply {
            invocationHandler = RemoveCertificateImpl(this)
            setAttributeDelegate(ExecutableByAdmin)
        }
    }

    override fun onShutdown() {
        trustListNode.openWithMasksMethodNode.apply {
            invocationHandler = MethodInvocationHandler.NOT_IMPLEMENTED
        }

        trustListNode.closeAndUpdateMethodNode.apply {
            invocationHandler = MethodInvocationHandler.NOT_IMPLEMENTED
        }

        trustListNode.addCertificateMethodNode.apply {
            invocationHandler = MethodInvocationHandler.NOT_IMPLEMENTED
        }

        trustListNode.removeCertificateMethodNode.apply {
            invocationHandler = MethodInvocationHandler.NOT_IMPLEMENTED
        }

        super.onShutdown()
    }

    inner class OpenWithMasksImpl(node: UaMethodNode) : OpenWithMasksMethod(node) {
        override fun invoke(
            context: InvocationContext,
            masks: UInteger,
            fileHandle: AtomicReference<UInteger>
        ) {

            val session = context.session.orElseThrow()

            val file = trustListManager.openTrustListFile(masks)
            val raf = RandomAccessFile(file, "r")

            val handle = uint(fileHandleSequence.incrementAndGet())

            handles.put(session.sessionId, handle, Pair(raf, ubyte(FileModeBit.Read.value)))

            fileHandle.set(handle)
        }
    }

    inner class CloseAndUpdateImpl(node: UaMethodNode) : CloseAndUpdateMethod(node) {
        override fun invoke(
            context: InvocationContext,
            fileHandle: UInteger,
            applyChangesRequired: AtomicReference<Boolean>
        ) {

            val session = context.session.orElseThrow()

            val filePair = handles.remove(session.sessionId, fileHandle)

            if (filePair != null) {
                val (file, mode) = filePair

                if (mode.isSet(FileObject.FileModeBit.Write)) {
                    file.channel.position(0L)
                    val bs = ByteArray(file.length().toInt())
                    file.readFully(bs)

                    val newTrustList = OpcUaDefaultBinaryEncoding.getInstance().decode(
                        ByteString.of(bs),
                        TrustListDataType.BinaryEncodingId,
                        EncodingLimits.DEFAULT,
                        OpcUaDataTypeManager.getInstance()
                    ) as? TrustListDataType

                    newTrustList?.let { trustList ->
                        val masks = trustList.specifiedLists

                        val factory = CertificateFactory.getInstance("X.509")

                        if (masks.isSet(TrustListMasks.TrustedCertificates)) {
                            val trustedCertificates = trustList.trustedCertificates?.mapNotNull { bs ->
                                CertificateUtil.decodeCertificate(bs.bytesOrEmpty())
                            } ?: emptyList()

                            trustListManager.setTrustedCertificates(trustedCertificates)
                        }

                        if (masks.isSet(TrustListMasks.TrustedCrls)) {
                            val trustedCrls = trustList.trustedCrls?.flatMap { bs ->
                                factory.generateCRLs(
                                    ByteArrayInputStream(bs.bytesOrEmpty())
                                ).mapNotNull { it as? X509CRL }
                            } ?: emptyList()

                            trustListManager.setTrustedCrls(trustedCrls)
                        }

                        if (masks.isSet(TrustListMasks.IssuerCertificates)) {
                            val issuerCertificates = trustList.issuerCertificates?.mapNotNull { bs ->
                                CertificateUtil.decodeCertificate(bs.bytesOrEmpty())
                            } ?: emptyList()

                            trustListManager.setIssuerCertificates(issuerCertificates)
                        }

                        if (masks.isSet(TrustListMasks.IssuerCrls)) {
                            val issuerCrls = trustList.issuerCrls?.flatMap { bs ->
                                factory.generateCRLs(
                                    ByteArrayInputStream(bs.bytesOrEmpty())
                                ).mapNotNull { it as? X509CRL }
                            } ?: emptyList()

                            trustListManager.setIssuerCrls(issuerCrls)
                        }
                    }

                    logger.debug("new TrustList: $newTrustList")
                }

                file.close()

                trustListNode.lastUpdateTime = DateTime.now()

                applyChangesRequired.set(false)
            } else {
                throw UaException(StatusCodes.Bad_NotFound)
            }

            throw UaException(StatusCodes.Bad_NotImplemented)
        }
    }

    inner class AddCertificateImpl(node: UaMethodNode) : AddCertificateMethod(node) {

        override fun invoke(
            context: InvocationContext,
            certificate: ByteString,
            isTrustedCertificate: Boolean
        ) {

            CertificateUtil.decodeCertificate(certificate.bytesOrEmpty()).let {
                if (isTrustedCertificate) {
                    trustListManager.addTrustedCertificate(it)

                    trustListManager.removeRejectedCertificate(CertificateUtil.thumbprint(it))
                } else {
                    trustListManager.addIssuerCertificate(it)
                }
            }

            trustListNode.lastUpdateTime = DateTime.now()
        }

    }

    inner class RemoveCertificateImpl(node: UaMethodNode) : RemoveCertificateMethod(node) {

        override fun invoke(
            context: InvocationContext,
            thumbprint: String,
            isTrustedCertificate: Boolean
        ) {

            val thumbprintBytes = ByteString.of(Hex.decode(thumbprint))

            val found = if (isTrustedCertificate) {
                trustListManager.removeTrustedCertificate(thumbprintBytes)
            } else {
                trustListManager.removeIssuerCertificate(thumbprintBytes)
            }

            if (!found) {
                throw UaException(StatusCodes.Bad_InvalidArgument)
            }

            trustListNode.lastUpdateTime = DateTime.now()
        }

    }

    // TODO override OpenMethodImpl to ensure only Read or Write+EraseExisting are allowed

}

private fun UInteger.isSet(masks: TrustListMasks): Boolean {
    return (this.toInt() and masks.value) == masks.value
}

private fun TrustListManager.openTrustListFile(masks: UInteger = UInteger.MAX): File {
    val trustList = this.getTrustListDataType()

    return File.createTempFile("TrustListDataType", null).apply {
        logger.debug("TrustList file created: {}", path)

        deleteOnExit()

        writeBytes(trustList.encode().bytesOrEmpty())
    }
}

private fun TrustListDataType.encode(): ByteString {
    return OpcUaDefaultBinaryEncoding.getInstance().encode(
        this,
        TrustListDataType.BinaryEncodingId,
        EncodingLimits.DEFAULT,
        OpcUaDataTypeManager.getInstance()
    ) as ByteString
}

private fun TrustListManager.getTrustListDataType(masks: UInteger = UInteger.MAX): TrustListDataType {
    val trustedCertificates = if (masks.isSet(TrustListMasks.TrustedCertificates)) {
        this.trustedCertificates.map { ByteString.of(it.encoded) }
    } else {
        emptyList()
    }

    val trustedCrls = if (masks.isSet(TrustListMasks.TrustedCrls)) {
        this.trustedCrls.map { ByteString.of(it.encoded) }
    } else {
        emptyList()
    }

    val issuerCertificates = if (masks.isSet(TrustListMasks.IssuerCertificates)) {
        this.issuerCertificates.map { ByteString.of(it.encoded) }
    } else {
        emptyList()
    }

    val issuerCrls = if (masks.isSet(TrustListMasks.IssuerCrls)) {
        this.issuerCrls.map { ByteString.of(it.encoded) }
    } else {
        emptyList()
    }

    return TrustListDataType(
        masks,
        trustedCertificates.toTypedArray(),
        trustedCrls.toTypedArray(),
        issuerCertificates.toTypedArray(),
        issuerCrls.toTypedArray()
    )
}
