package google.registry.model.tmch;

import google.registry.model.transaction.DatabaseTransactionManager;
import google.registry.schema.tmch.ClaimsList;
import javax.persistence.EntityManager;

public class ClaimsListDatabaseDao implements ClaimsListDao {

  @Override
  public void save(ClaimsList claimsList) {
    EntityManager em = DatabaseTransactionManager.getEntityManager();
    em.persist(claimsList);
  }

  @Override
  public ClaimsList getCurrentList() {
    EntityManager em = DatabaseTransactionManager.getEntityManager();
    long revisionId =
        (long) em.createQuery("SELECT MAX(revisionId) FROM ClaimsList").getSingleResult();
    return em.find(ClaimsList.class, revisionId);
  }
}
