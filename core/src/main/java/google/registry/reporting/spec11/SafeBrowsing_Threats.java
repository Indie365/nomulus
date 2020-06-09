package google.registry.reporting.spec11;

import com.googlecode.objectify.annotation.Index;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import org.joda.time.DateTime;
import google.registry.schema.replay.SqlEntity;

import javax.persistence.*;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

@Entity
@Table(name = "safebrowsing_threats")
// TODO(legina): DATABASE INDEXES
public class SafeBrowsing_Threats extends ImmutableObject implements Buildable {

  /** The type of threat detected. */
  public enum ThreatType {
    PHISHING,
    MALWARE,
    SOCIAL_ENGINEERING,
    UNWANTED_SOFTWARE
  }

  /** An auto-generated identifier and unique primary key for this entity. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  Long id;

  /** The name of the offending domain */
  @Column(name = "domain_name", nullable = false)
  String domainName;

  /** The type of threat detected. */
  @Column(name = "threat_type", nullable = false)
  @Enumerated(EnumType.STRING)
  ThreatType threatType;

  /** Primary key of the domain table and unique identifier for all EPP resources. */
  @Column(name = "repo_id", nullable = false)
  String repoId;

  /** ID of the registrar. */
  @Column(name = "registrar_id", nullable = false)
  String registrarId;

  /** Date of run. */
  @Column(name = "check_date", nullable = false)
  DateTime checkDate;

  /** The domain's top-level domain. */
  @Column(name = "tld", nullable = false)
  String tld;

  public Long getID() {
    return id;
  }

  public String getDomainName() {
    return domainName;
  }

  public ThreatType getThreatType() {
    return threatType;
  }

  public String getRepoId() {
    return repoId;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public DateTime getCheckDate() {
    return checkDate;
  }

  public String getTld() {
    return tld;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /**
   * A builder for constructing {@link google.registry.reporting.spec11.SafeBrowsing_Threats}, since
   * it is immutable.
   */
  public static class Builder extends Buildable.Builder<SafeBrowsing_Threats> {
    public Builder() {}

    private Builder(SafeBrowsing_Threats instance) {
      super(instance);
    }

    @Override
    public SafeBrowsing_Threats build() {
      checkArgumentNotNull(getInstance().id, "ID cannot be null");
      checkArgumentNotNull(getInstance().domainName, "Domain name cannot be null");
      checkArgumentNotNull(getInstance().threatType, "Threat type cannot be null");
      checkArgumentNotNull(getInstance().repoId, "Repo ID cannot be null");
      checkArgumentNotNull(getInstance().registrarId, "Registrar ID cannot be null");
      checkArgumentNotNull(getInstance().checkDate, "Check date cannot be null");
      checkArgumentNotNull(getInstance().tld, "TLD cannot be null");

      return super.build();
    }

    public Builder setId(Long id) {
      getInstance().id = id;
      return this;
    }

    public Builder setDomainName(String domainName) {
      getInstance().domainName = domainName;
      return this;
    }

    public Builder setThreatType(ThreatType threatType) {
      getInstance().threatType = threatType;
      return this;
    }

    public Builder setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return this;
    }

    public Builder setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setCheckDate(DateTime checkDate) {
      getInstance().checkDate = checkDate;
      return this;
    }

    public Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }
  }
}
