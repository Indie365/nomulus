package google.registry.bsa.http;

import com.google.api.client.http.HttpTransport;
import google.registry.bsa.common.BlockList;
import java.util.stream.Stream;
import javax.inject.Inject;

public class BsaHttpClient {

  private final HttpTransport httpTransport;

  @Inject
  BsaHttpClient(HttpTransport httpTransport) {
    this.httpTransport = httpTransport;
  }

  Stream<String> fetchBlockList(BlockList listName) {
    return null;
  }

  public void reportOrderProcessingStatus(String data) {}

  void addUnblockableDomains(String data) {}

  void removeUnblockableDomains(String data) {}
}
