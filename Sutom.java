///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS info.picocli:picocli:4.6.3
//DEPS com.microsoft.playwright:playwright:1.39.0
//DEPS commons-io:commons-io:2.15.0
//DEPS com.github.fge:throwing-lambdas:0.5.0
//DEPS org.apache.commons:commons-lang3:3.13.0
//DEPS com.ibm.icu:icu4j:74.2


import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.text.html.CSS;

import org.apache.commons.io.FileUtils;

import com.ibm.icu.text.Transliterator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "Sutom", mixinStandardHelpOptions = true, version = "Pedantix 0.1",
        description = "Sutom solver made with jbang")
class Sutom implements Callable<String> {
	public static final Logger logger = Logger.getLogger(Sutom.class.getName());
	private static final Map<Character, Double> LETTER_FREQUENCIES = Map.ofEntries(
				Map.entry('E', 12.10),
				Map.entry('A', 7.1),
				Map.entry('I', 6.5),
				Map.entry('S', 6.5),
				Map.entry('N', 6.3),
				Map.entry('R', 6.0),
				Map.entry('T', 5.9),
				Map.entry('O', 5.0),
				Map.entry('L', 4.9),
				Map.entry('U', 4.4),
				Map.entry('D', 3.6),
				Map.entry('C', 3.1),
				Map.entry('M', 2.6),
				Map.entry('P', 2.4),
				Map.entry('G', 1.2),
				Map.entry('B', 1.1),
				Map.entry('V', 1.1),
				Map.entry('H', 1.1),
				Map.entry('F', 1.1),
				Map.entry('Q', 0.6),
				Map.entry('Y', 0.4),
				Map.entry('X', 0.3),
				Map.entry('J', 0.3),
				Map.entry('K', 0.2),
				Map.entry('W', 0.1),
				Map.entry('Z', 0.1));

	// Downloaded from https://github.com/Lionel-D/sutom/blob/main/data/mots.txt
    @Option(description = "Used words file", names= {"--words-file"}, defaultValue = "mots_pour_sutom.txt")
    File wordsFile;
    @Option(description="The url to play SUTOM", names = {"--sutom-url"}, defaultValue = "https://sutom.nocle.fr")
    String sutomUrl;
    
    private Map<String, Double> WORD_FREQUENCIES = Collections.synchronizedMap(new TreeMap<>());

    public static void main(String... args) {
        int exitCode = new CommandLine(new Sutom()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public String call() throws Exception { // your business logic goes here...
		logger.info(String.format("Loading words"));
    	Transliterator accentsConverter = Transliterator.getInstance("Any-Latin; NFD; [:M:] Remove; NFC; [^\\p{ASCII}] Remove");
    	List<String> words = FileUtils.readLines(wordsFile, "UTF-8").stream()
    			.parallel()
    			.filter(s -> !s.contains("-"))
    			.map(s -> accentsConverter.transform(s))
    			.map(s -> s.toUpperCase().trim())
    			.collect(Collectors.toList());
		logger.info(String.format("Loaded %d words", words.size()));
        try (Playwright playwright = Playwright.create()) {
        	BrowserContext context = createPlaywrightContext(playwright);
        	try(Page page = context.newPage()) {
        		page.navigate(sutomUrl);
        		// Now it's time to work
        		return playSutom(words, page);
        	}
        }
    }

	private String playSutom(List<String> words, Page page) {
		Locator table = page.locator("div#grille table");
		var puzzleDefinition = definePuzzle(page, table);
		logger.info(String.format("Found table %s", puzzleDefinition));
		List<String> wordsMatchingConstraints = words.stream()
				.filter(w -> w.length()==puzzleDefinition.cols)
				.collect(Collectors.toList());
		logger.info(String.format("There are %d right sized words", wordsMatchingConstraints.size()));
		wordsMatchingConstraints.stream()
			.parallel()
			.forEach(w -> WORD_FREQUENCIES.put(w, computeWordFrequency(w)));
		// Now we know the puzzle complexity, let's play!
		int row = 0;
		var letters = readLetters(page, table, puzzleDefinition, row, null);
		var firstLetter = Character.toString(letters.get(0).character);
		// First line is very special case where we simply check the first letter
		// There are many letters, so do things as fast possible
		wordsMatchingConstraints = wordsMatchingConstraints.stream().parallel()
				.filter(w -> w.startsWith(firstLetter))
    			.sorted(wordsByFrequencies())
				.collect(Collectors.toList());
		// We need a delay between enter and scan because Playwright tends to get crazy
		final int WAIT_DELAY_MS = 500*puzzleDefinition.cols;
		// And now, filter words with the right first letter
		while(row<puzzleDefinition.cols) {
			if(wordsMatchingConstraints.size()>100) {
				logger.info(String.format("Words list reduced to %d", wordsMatchingConstraints.size()));
			} else {
				logger.info(String.format("Words list reduced to %s", wordsMatchingConstraints));
			}
			String toAttempt = findBestWord(wordsMatchingConstraints, letters);
			page.keyboard().type(toAttempt);
			page.keyboard().press("Enter");
			logger.info(String.format("Waiting %d ms", WAIT_DELAY_MS));
			page.waitForTimeout(WAIT_DELAY_MS);
			letters = readLetters(page, table, puzzleDefinition, row, letters);
			if(foundWord(letters)) {
				logger.info(String.format("The word you're looking for is \"%s\" (found in %d tries)", toAttempt, row));
				return toAttempt;
			} else {
				wordsMatchingConstraints = findWordsMatchinConstraints(wordsMatchingConstraints, letters);
			}
			row++;
		}
		return "Couldn't find any word";
	}
	private List<Sutom.Letter> removeCharactersVisibleNowhereFrom(List<Letter> letters) {
		Set<Character> charactersNowhere = letters.stream()
			.filter(l -> l.matching==Matching.NOWHERE)
			.flatMap(l -> l.forbidden.stream())
			.distinct()
			.collect(Collectors.toSet());
		if(charactersNowhere.isEmpty())
			return letters;
		return letters.stream()
				.map(l -> {
					Set<Character> forbidden = new HashSet<Character>(charactersNowhere);
					if(l.matching==Matching.RIGHT_PLACE)
						forbidden.remove(l.character);
					forbidden.addAll(l.forbidden);
					return new Letter(l.character, forbidden, l.matching);
				})
				.collect(Collectors.toList());
	}

	private boolean foundWord(List<Letter> letters) {
    	for(Letter letter : letters) {
    		if(letter.matching!=Matching.RIGHT_PLACE)
    			return false;
    	}
    	return true;
	}

	private String findBestWord(List<String> words, List<Sutom.Letter> letters) {
		return words.stream()
				.findFirst()
				.orElseThrow(() -> new UnsupportedOperationException("No string found matching constraints"))
				;
	}
    public double computeWordFrequency(String word) {
    	Map<Character, Integer> count = new TreeMap<Character, Integer>();
    	for(Character c : word.toCharArray()) {
    		count.compute(c, (k, v) -> v==null ? 1 : v+1);
    	}
    	return count.entrySet().stream()
    		.mapToDouble(entry -> LETTER_FREQUENCIES.get(entry.getKey()) * count.size() /*/entry.getValue()*/)
    		.sum();
    }
    private List<String> findWordsMatchinConstraints(List<String> wordsMatchingConstraints,
			List<Sutom.Letter> letters) {
    	logger.info(String.format("There are %d candidates", wordsMatchingConstraints.size()));
    	List<String> returned = wordsMatchingConstraints.stream()
    			.filter(w -> wordMatchConstraints(w, letters))
    			.sorted(wordsByFrequencies())
    			.collect(Collectors.toList());
    	logger.info(String.format("There are %d matching words", returned.size()));
    	return returned;
	}

	private Comparator<String> wordsByFrequencies() {
		return Comparator.comparingDouble((String s) -> WORD_FREQUENCIES.get(s)).reversed();
	}

	private boolean wordMatchConstraints(String w, List<Letter> letters) {
		char[] characters = w.toCharArray();
		for (int i = 0; i < characters.length; i++) {
			char c = characters[i];
			Letter l = letters.get(i);
			switch(l.matching) {
			case BAD_PLACE:
			case NOWHERE:
				if (c==l.character)
					return false;
				break;
			case RIGHT_PLACE:
				if(c!=l.character)
					return false;
				break;
			default:
			}
		}
		return true;
	}
	enum Matching {
    	RIGHT_PLACE,
    	BAD_PLACE,
    	MISSING,
    	NOWHERE
    }
    record Letter(Character character, Set<Character> forbidden, Matching matching) {}
    private List<Letter> readLetters(Page page, Locator table, PuzzleDefinition puzzleDefinition, int rowIndex, List<Letter> previousTurnLetters) {
    	Locator rows = table.locator("tr");
    	Locator row = rows.nth(rowIndex).locator("td");
    	List<Letter> returned = new ArrayList<Letter>(puzzleDefinition.cols);
    	for (int i = 0; i < puzzleDefinition.cols; i++) {
			Locator cell = row.nth(i);
			Letter letter = readLetterAtCell(cell, previousTurnLetters==null ? null : previousTurnLetters.get(i));
			returned.add(letter);
		}
		// There is a little design flaw regarding letters found nowhere.
		// The easiest way to solve that is to add those nowhere letters
		// In all slots as forbidden letters.
		// It's just, well, not so good design :-/
		returned = removeCharactersVisibleNowhereFrom(returned);

		return returned;
	}
	private Letter readLetterAtCell(Locator cell, Letter previousLetter) {
		String innerText = cell.innerText();
		char text = innerText.charAt(0);
		String cssClass = cell.getAttribute("class");
		Set<Character> forbidden = new HashSet<Character>();
		Matching matching;
		if(text=='.') {
			matching = Matching.MISSING;
		} else {
			// Previous turn letters is empty at first turn
			if(previousLetter==null) {
				matching = Matching.RIGHT_PLACE;
			} else {
				// Weirdly enough, this one sometimes fails
				while(cssClass==null) {
					cssClass = cell.getAttribute("class");
				}
				forbidden.addAll(previousLetter.forbidden);
				if(cssClass.contains("bien-place")) {
					matching = Matching.RIGHT_PLACE;
				} else if(cssClass.contains("mal-place")) {
					matching = Matching.BAD_PLACE;
					forbidden.add(text);
				} else if(cssClass.contains("non-trouve")) {
					matching = Matching.NOWHERE;
					forbidden.add(text);
				} else {
					throw new UnsupportedOperationException("This case should never happen");
				}
			}
		}
		return new Letter(text, forbidden, matching);
	}
	record PuzzleDefinition(int rows, int cols) {}
	private PuzzleDefinition definePuzzle(Page page, Locator table) {
		table.waitFor();
		Locator rows = table.locator("tr");
		Locator cols = rows.first().locator("td");
		
		return new PuzzleDefinition(rows.count(), cols.count());
	}

	private BrowserContext createPlaywrightContext(Playwright playwright) {
    	Browser chromium = playwright.chromium().launch(
    			new BrowserType.LaunchOptions()
    				.setHeadless(false)
    			);
    	BrowserContext context = chromium.newContext(new NewContextOptions()
    			.setJavaScriptEnabled(true));
		// Disable all resources coming from any domain that is not
		// mvnrepository or wayback machine
//		context.route(url -> !(url.contains("mvnrepository.com") || url.contains("web.archive.com")), 
//				route -> route.abort());
    	context.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
    	context.setDefaultTimeout(0);
    	return context;
	}

}
