package mezz.jei.forge;

import mezz.jei.forge.events.PermanentEventSubscriptions;
import mezz.jei.forge.network.NetworkHandler;

public class JustEnoughItemsClientSafeRunner {
	private final NetworkHandler networkHandler;
	private final PermanentEventSubscriptions subscriptions;

	public JustEnoughItemsClientSafeRunner(
		NetworkHandler networkHandler,
		PermanentEventSubscriptions subscriptions
	) {
		this.networkHandler = networkHandler;
		this.subscriptions = subscriptions;
	}

	public void registerClient() {
		JustEnoughItemsClient jeiClient = new JustEnoughItemsClient(networkHandler, subscriptions);
		jeiClient.register();
	}
}
