package net.rozukke.elci;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.bukkit.Location;
import org.bukkit.World;
//import org.bukkit.craftbukkit.v1_9_R1.CraftWorld;
import org.testng.Assert;

import java.nio.channels.SocketChannel;

public class SessionStepDefs {

	@Mocked
	private World world;

	private RemoteSession remoteSession;
	private Location requestedPosition;
	private String locationAsString;
	private Location location;

	@Mocked
	private ELCIPlugin plugin;

	@Mocked
	private SocketChannel socket;


	@Given("^The location type (.*)$")
	public void theLocationType(String type) throws Throwable {

		new MockUp<RemoteSession>() {
			@Mock
			public void init() {
				//Avoid the real init as it errors out and we don't need it for our tests
			}
		};

		remoteSession = new RemoteSession(plugin, socket);
	}

	@And("^a spawn point of (.*), (.*), (.*)$")
	public void aSpawnPointOf(double x, double y, double z) throws Throwable {
		Location origin = new Location(world, x, y, z);
		remoteSession.setOrigin(origin);
	}

	@And("^a location point of (\\d+), (\\d+), (-?\\d+)$")
	public void aLocationPointOf(int x, int y, int z) throws Throwable {
		requestedPosition = new Location(world, x, y, z);
	}

	@When("^a request for a relative location is made$")
	public void aRequestForARelativeLocationIsMade() throws Throwable {
		locationAsString = remoteSession.locationToRelative(requestedPosition);
	}

	@Then("^the relative location should have co-ordinates (.*), (.*), (.*)$")
	public void theRelativeLocationShouldHaveCoOrdinates(String x, String y, String z) throws Throwable {
		String[] split = locationAsString.split(",");
		Assert.assertEquals(split[0], x);
		Assert.assertEquals(split[1], y);
		Assert.assertEquals(split[2], z);
	}

	@When("^a request for a relative block location is made at (.*), (.*), (.*)$")
	public void aRequestForARelativeBlockLocationIsMade(String x, String y, String z) throws Throwable {
		location = remoteSession.parseRelativeBlockLocation(x, y, z);
	}

	@Then("^the block location should have co-ordinates (.*), (.*), (.*)$")
	public void theBlockLocationShouldHaveCoOrdinates(int x, int y, int z) throws Throwable {
		Assert.assertEquals(location.getBlockX(), x);
		Assert.assertEquals(location.getBlockY(), y);
		Assert.assertEquals(location.getBlockZ(), z);
	}

	@When("^a request for a relative location is made at (.*), (.*), (.*)$")
	public void aRequestForARelativeLocationIsMade(String x, String y, String z) throws Throwable {
		location = remoteSession.parseRelativeLocation(x, y, z);
	}

	@Then("^the location should have co-ordinates (.*), (.*), (.*)$")
	public void theLocationShouldHaveCoOrdinates(double x, double y, double z) throws Throwable {
		Assert.assertEquals(location.getX(), x);
		Assert.assertEquals(location.getY(), y);
		Assert.assertEquals(location.getZ(), z);
	}
}
