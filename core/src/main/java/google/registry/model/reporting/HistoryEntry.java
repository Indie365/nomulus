// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.reporting;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.batch.ExpandRecurrencesAction;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.annotations.IdAllocation;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import org.apache.commons.lang3.BooleanUtils;
import org.joda.time.DateTime;

/**
 * A record of an EPP command that mutated a resource.
 *
 * <p>This abstract class has three subclasses that include the parent resource itself and are
 * persisted to Cloud SQL.
 */
@MappedSuperclass
@Access(AccessType.FIELD)
@IdClass(HistoryEntryId.class)
public abstract class HistoryEntry extends ImmutableObject
    implements Buildable, UnsafeSerializable {

  /** Represents the type of history entry. */
  public enum Type {
    CONTACT_CREATE,
    CONTACT_DELETE,
    CONTACT_DELETE_FAILURE,
    CONTACT_PENDING_DELETE,
    CONTACT_TRANSFER_APPROVE,
    CONTACT_TRANSFER_CANCEL,
    CONTACT_TRANSFER_REJECT,
    CONTACT_TRANSFER_REQUEST,
    CONTACT_UPDATE,
    /**
     * Used for history entries that were allocated as a result of a domain application.
     *
     * <p>Domain applications (and thus allocating from an application) no longer exist, but we have
     * existing domains in the system that were created via allocation and thus have history entries
     * of this type under them, so this is retained for legacy purposes.
     */
    @Deprecated
    DOMAIN_ALLOCATE,
    /**
     * Used for domain registration autorenews explicitly logged by {@link ExpandRecurrencesAction}.
     */
    DOMAIN_AUTORENEW,
    DOMAIN_CREATE,
    DOMAIN_DELETE,
    DOMAIN_RENEW,
    DOMAIN_RESTORE,
    DOMAIN_TRANSFER_APPROVE,
    DOMAIN_TRANSFER_CANCEL,
    DOMAIN_TRANSFER_REJECT,
    DOMAIN_TRANSFER_REQUEST,
    DOMAIN_UPDATE,
    HOST_CREATE,
    HOST_DELETE,
    HOST_DELETE_FAILURE,
    HOST_PENDING_DELETE,
    HOST_UPDATE,
    /** Resource was created by an escrow file import. */
    RDE_IMPORT,
    /**
     * A synthetic history entry created by a tool or back-end migration script outside the scope of
     * usual EPP flows. These are sometimes needed to serve as parents for billing events or poll
     * messages that otherwise wouldn't have a suitable parent.
     */
    SYNTHETIC
  }

  /** The autogenerated id of this event. */
  @Id
  @IdAllocation
  @Column(nullable = false, name = "historyRevisionId")
  protected Long revisionId;

  /**
   * The repo ID of the embedded {@link EppResource} that this event mutated.
   *
   * <p>Note that the embedded EPP resource is of a base type for which the repo ID field is
   * {@code @Transient}, which is NOT persisted as part of the embedded entity. After a {@link
   * HistoryEntry} is loaded from SQL, the {@link #postLoad()} methods re-populates the field inside
   * the EPP resource.
   */
  @Id protected String repoId;

  /** The type of history entry. */
  @Column(nullable = false, name = "historyType")
  @Enumerated(EnumType.STRING)
  Type type;

  /**
   * The actual EPP xml of the command, stored as bytes to be agnostic of encoding.
   *
   * <p>Changes performed by backend actions would not have EPP requests to store.
   */
  @Column(name = "historyXmlBytes")
  byte[] xmlBytes;

  /** The time the command occurred, represented by the transaction time. */
  @Column(nullable = false, name = "historyModificationTime")
  DateTime modificationTime;

  /** The id of the registrar that sent the command. */
  @Column(name = "historyRegistrarId")
  String clientId;

  /** Transaction id that made this change, or null if the entry was not created by a flow. */
  @Nullable
  @AttributeOverrides({
    @AttributeOverride(
        name = "clientTransactionId",
        column = @Column(name = "historyClientTransactionId")),
    @AttributeOverride(
        name = "serverTransactionId",
        column = @Column(name = "historyServerTransactionId"))
  })
  Trid trid;

  /** Whether this change was created by a superuser. */
  @Column(nullable = false, name = "historyBySuperuser")
  boolean bySuperuser;

  /** Reason for the change. */
  @Column(name = "historyReason")
  String reason;

  /** Whether this change was requested by a registrar. */
  @Column(name = "historyRequestedByRegistrar")
  Boolean requestedByRegistrar;

  public long getRevisionId() {
    // For some reason, Hibernate throws NPE during some initialization phases if we don't deal with
    // the null case. Setting the id to 0L when it is null should be fine because 0L for primitive
    // type is considered as null for wrapper class in the Hibernate context.
    return revisionId == null ? 0L : revisionId;
  }

  protected abstract EppResource getResource();

  public Class<? extends EppResource> getResourceType() {
    return getResource().getClass();
  }

  public String getRepoId() {
    return repoId;
  }

  public HistoryEntryId getHistoryEntryId() {
    return new HistoryEntryId(repoId, revisionId);
  }

  public Type getType() {
    return type;
  }

  public byte[] getXmlBytes() {
    return xmlBytes == null ? null : xmlBytes.clone();
  }

  public DateTime getModificationTime() {
    return modificationTime;
  }

  public String getRegistrarId() {
    return clientId;
  }

  /** Returns the TRID, which may be null if the entry was not created by a normal flow. */
  @Nullable
  public Trid getTrid() {
    return trid;
  }

  public boolean getBySuperuser() {
    return bySuperuser;
  }

  public String getReason() {
    return reason;
  }

  public Boolean getRequestedByRegistrar() {
    return requestedByRegistrar;
  }

  public abstract Optional<? extends EppResource> getResourceAtPointInTime();

  protected void processResourcePostLoad() {
    // Post-Registry 3.0 entity should always have the resource field, whereas pre-Registry 3.0
    // will return a null resource.
    if (getResource() != null && getResource().getRepoId() == null) {
      // The repoId field in EppResource is transient, so we go ahead and set it to the value read
      // from SQL.
      getResource().setRepoId(repoId);
    }
  }

  @PostLoad
  protected void postLoad() {
    processResourcePostLoad();
  }

  @Override
  public abstract Builder<? extends HistoryEntry, ?> asBuilder();

  protected static void copy(HistoryEntry src, HistoryEntry dst) {
    dst.revisionId = src.revisionId;
    dst.type = src.type;
    dst.xmlBytes = src.xmlBytes;
    dst.modificationTime = src.modificationTime;
    dst.clientId = src.clientId;
    dst.trid = src.trid;
    dst.bySuperuser = src.bySuperuser;
    dst.reason = src.reason;
    dst.requestedByRegistrar = src.requestedByRegistrar;
  }

  /** A builder for {@link HistoryEntry} since it is immutable */
  public abstract static class Builder<T extends HistoryEntry, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {
    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    // Used to fill out the fields in this object from an object which may not be exactly the same
    // as the class T, where both classes still subclass HistoryEntry
    public B copyFrom(HistoryEntry historyEntry) {
      copy(historyEntry, getInstance());
      return thisCastToDerived();
    }

    public B copyFrom(HistoryEntry.Builder<? extends HistoryEntry, ?> builder) {
      return copyFrom(builder.getInstance());
    }

    @Override
    public T build() {
      checkArgumentNotNull(getInstance().getResource(), "EPP resource must be specified");
      checkArgumentNotNull(getInstance().repoId, "repoId must be specified");
      checkArgumentNotNull(getInstance().type, "History entry type must be specified");
      checkArgumentNotNull(getInstance().modificationTime, "Modification time must be specified");
      checkArgumentNotNull(getInstance().clientId, "Registrar ID must be specified");
      checkArgument(
          !getInstance().type.equals(Type.SYNTHETIC)
              || BooleanUtils.isNotTrue(getInstance().requestedByRegistrar),
          "Synthetic history entries cannot be requested by a registrar");
      return super.build();
    }

    public B setRevisionId(Long revisionId) {
      getInstance().revisionId = revisionId;
      return thisCastToDerived();
    }

    protected B setRepoId(EppResource eppResource) {
      if (eppResource != null) {
        getInstance().repoId = eppResource.getRepoId();
      }
      return thisCastToDerived();
    }

    public B setType(Type type) {
      getInstance().type = type;
      return thisCastToDerived();
    }

    public B setXmlBytes(byte[] xmlBytes) {
      getInstance().xmlBytes = xmlBytes == null ? null : xmlBytes.clone();
      return thisCastToDerived();
    }

    public B setModificationTime(DateTime modificationTime) {
      getInstance().modificationTime = modificationTime;
      return thisCastToDerived();
    }

    public B setRegistrarId(String registrarId) {
      getInstance().clientId = registrarId;
      return thisCastToDerived();
    }

    public B setTrid(Trid trid) {
      getInstance().trid = trid;
      return thisCastToDerived();
    }

    public B setBySuperuser(boolean bySuperuser) {
      getInstance().bySuperuser = bySuperuser;
      return thisCastToDerived();
    }

    public B setReason(String reason) {
      getInstance().reason = reason;
      return thisCastToDerived();
    }

    public B setRequestedByRegistrar(Boolean requestedByRegistrar) {
      getInstance().requestedByRegistrar = requestedByRegistrar;
      return thisCastToDerived();
    }
  }

  public static <E extends EppResource>
      HistoryEntry.Builder<? extends HistoryEntry, ?> createBuilderForResource(E parent) {
    if (parent instanceof DomainBase) {
      return new DomainHistory.Builder().setDomain((DomainBase) parent);
    } else if (parent instanceof ContactBase) {
      return new ContactHistory.Builder().setContact((ContactBase) parent);
    } else if (parent instanceof HostBase) {
      return new HostHistory.Builder().setHost((HostBase) parent);
    } else {
      throw new IllegalStateException(
          String.format(
              "Class %s does not have an associated HistoryEntry", parent.getClass().getName()));
    }
  }

  /** Class to represent the composite primary key of a {@link HistoryEntry}. */
  @Embeddable
  @Access(AccessType.PROPERTY)
  public static class HistoryEntryId extends ImmutableObject implements UnsafeSerializable {

    private String repoId;

    private long revisionId;

    protected HistoryEntryId() {}

    public HistoryEntryId(String repoId, long revisionId) {
      this.repoId = repoId;
      this.revisionId = revisionId;
    }

    /** Returns the {@code history_revision_id} of the {@link HistoryEntry}. */
    public long getRevisionId() {
      return revisionId;
    }

    @SuppressWarnings("unused")
    private void setRevisionId(long revisionId) {
      this.revisionId = revisionId;
    }

    /** Returns the {@code [domain|contact|host]_repo_id} of the {@link HistoryEntry}. */
    public String getRepoId() {
      return repoId;
    }

    @SuppressWarnings("unused")
    private void setRepoId(String repoId) {
      this.repoId = repoId;
    }
  }
}
