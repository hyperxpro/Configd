package io.configd.kms;

/**
 * {@link java.util.ServiceLoader} SPI by which an <em>optional, out-of-tree</em> module
 * (e.g. {@code configd-kms-aws}) advertises its {@link KmsProvider} to the core without
 * the core compile-depending on it. The core lists each factory's {@link #type()} and
 * instantiates the one selected by {@code configd.raft.encryption.kms.provider}.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p>This is the discovery substrate; selection is still by explicit name (mirroring
 * {@code NettyTransport.select()}). A {@code configd-kms-aws} jar ships
 * {@code META-INF/services/io.configd.kms.KmsProviderFactory} naming its factory; the
 * jar's mere presence on the classpath registers {@code aws-kms}, and the core stays
 * cloud-SDK-free.
 */
public interface KmsProviderFactory {

    /** Stable discriminator, e.g. {@code "aws-kms"}. Matches {@link KmsProvider#type()}. */
    String type();

    /** Instantiates the provider from configuration (no key material is passed in). */
    KmsProvider create(KmsConfig config);
}
