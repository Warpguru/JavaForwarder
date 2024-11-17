package at.test.nist;

import org.jnbis.api.Jnbis;

public class NistConverter {

	public static void main(String[] args) {
		Jnbis.wsq().decode("D:/sample.wsq").toPng().asFile("D:/sample.png");
		Jnbis.wsq().decode("D:/nistsample.bin").toPng().asFile("D:/nistsample.png");
	}
}
