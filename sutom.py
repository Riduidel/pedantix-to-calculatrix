from pywinauto import Desktop, Application
import os
import cv2
import numpy as np
import pytesseract
import codecs
import unidecode
from collections import OrderedDict, Counter

IMAGES_FOLDER = "sutom_images/"

# Those frequencies have been fetched from 

FREQUENCIES = {
'E': 	12.10,
'A': 	7.11,
'I': 	6.59,
'S': 	6.51,
'N': 	6.39,
'R': 	6.07,
'T': 	5.92,
'O': 	5.02,
'L': 	4.96,
'U': 	4.49,
'D': 	3.67,
'C': 	3.18,
'M': 	2.62,
'P': 	2.49,
'G': 	1.23,
'B': 	1.14,
'V': 	1.11,
'H': 	1.11,
'F': 	1.11,
'Q':	0.65,
'Y':	0.46,
'X':	0.38,
'J':	0.34,
'K':	0.29,
'W':	0.17,
'Z':	0.15
}

def find_sutom_rows_in(initial_image_path):
    # Square detection courtesy of https://stackoverflow.com/q/55169645/15619
    initial_image = cv2.imread(initial_image_path)
    # Image is grayed to simplify process
    gray = cv2.cvtColor(initial_image, cv2.COLOR_BGR2GRAY)
    cv2.imwrite(IMAGES_FOLDER+"0_1_grayed.png", gray)
    # setting threshold of gray image
    _, threshold = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY)
    cv2.imwrite(IMAGES_FOLDER+"0_2_thresholded.png", threshold)
    # Unfortunatly, this need a black/white image (well, maybe there is another way, but I will have to read the documentation)
    # Using findContours, since we know SUTOM shows each letter in a square
    # See https://docs.opencv.org/3.4/d4/d73/tutorial_py_contours_begin.html
    # https://docs.opencv.org/4.6.0/d3/dc0/group__imgproc__shape.html#gadf1ad6a0b82947fa1fe3c3d497f260e0
    contours, hierarchy = cv2.findContours( threshold, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
    lines = OrderedDict()
    # list for storing names of shapes
    for contour in contours:
        # cv2.approxPloyDP() function to approximate the shape
        # https://docs.opencv.org/4.x/d3/dc0/group__imgproc__shape.html#ga0012a5fdaea70b8a9970165d98722b4c
        approx = cv2.approxPolyDP(contour, 0.01 * cv2.arcLength(contour, True), True)
        if len(approx) == 4:
            # compute the bounding box of the contour and use the
            # bounding box to compute the aspect ratio
            (x, y, w, h) = cv2.boundingRect(approx)
            ar = w / float(h)
            # a square will have an aspect ratio that is approximately
            # equal to one, otherwise, the shape is a rectangle
            if ar >= 0.95 and ar<=1.05:
                # Last test : we search for squares of more than 10 px width, and less than 100
                # Because we know which size are SUTOM squares
                if w>10 and w<100:
                    if not y in lines:
                        lines[y] = OrderedDict()
                    lines[y][x] = approx
    # Unfortunatly, OrderedDict sorts in descending order (which means from last row to first, and from last col the first)
    # So let's reverse
    lines = OrderedDict(reversed(lines.items()))
    # Now we have approximate shapes, let's draw the celles (to make sure our ordered dict is correctly done)
    for row_index, line in enumerate(lines):
        lines[line] = OrderedDict(reversed(lines[line].items()))
        for col_index, cell in enumerate(lines[line]):
            contour = lines[line][cell]
            # See https://docs.opencv.org/4.x/d4/d73/tutorial_py_contours_begin.html
            cv2.drawContours(initial_image, [contour], 0, (0, 0, 255), 5)
            # finding center point of shape
            M = cv2.moments(contour)
            if M['m00'] != 0.0:
                x = int(M['m10']/M['m00'])
                y = int(M['m01']/M['m00'])
            cv2.putText(initial_image, '{},{}'.format(row_index, col_index), (x, y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
            # displaying the image after drawing contours
            cv2.imwrite(IMAGES_FOLDER+'0_3_shapes.png', initial_image)
    # Now sort rows and cols (in order to make sure proposals are correctly sorted)
    # I know it's not pythonic, but that's how I do
    return lines
def dominant_color(a):
    '''
    Courtesy of https://stackoverflow.com/a/50900143/15619
    '''
    # See https://numpy.org/doc/stable/reference/generated/numpy.unique.html
    colors, count = np.unique(a.reshape(-1,a.shape[-1]), axis=0, return_counts=True)
    return colors[count.argmax()]

def status_for_color(color):
    '''
    Find the cell status according to the dominant color
    Possible values are
    - missing (for blue backgroup)
    - misplaced (for yellow background)
    - found (for red background)
    '''
    return 'missing'
def detect_letters_and_status_in(image_path, row):
    cv2_image = cv2.imread(image_path)
    returned = []
    for cell_index in row:
        cell = row[cell_index]
        top_left = cell[0]
        bottom_right = cell[2]
        # The +5/-5 offsets are here to make sure the SUTOM border cells are not in cell images
        top_left_x = top_left[0][0]+5
        top_left_y = top_left[0][1]+5
        bottom_right_x = bottom_right[0][0]-5
        bottom_right_y =bottom_right[0][1]-5
        # https://stackoverflow.com/a/15589825/15619
        # Yeah the cropping order with y before x and start->end given as key/value is super-weird
        cropped_to_cell = cv2_image[top_left_y:bottom_right_y, top_left_x:bottom_right_x]
        # And now we can detect letter in each element!
        returned.append({
            # The config flag is special weird, but StackOverflow has the answer! https://stackoverflow.com/a/60979089/15619
            'text':pytesseract.image_to_string(cropped_to_cell, config='--psm 10')[0:1], 
            'status':status_for_color(dominant_color(cropped_to_cell))
            })
    return returned

def capture_sutom_window(index):
    app = Application(backend="uia").connect(title_re="SUTOM.*")
    hwin = app.window(title_re="SUTOM.*")
    hwin.set_focus()
    initial_image = hwin.capture_as_image()
    if not os.path.isdir(IMAGES_FOLDER):
        
        # if the demo_folder2 directory is 
        # not present then create it.
        os.makedirs(IMAGES_FOLDER)
    initial_image_path  = IMAGES_FOLDER+"{}_0.png".format(index)
    initial_image.save(initial_image_path)
    return initial_image_path

def get_letters_frequency(word):
    '''
    This method computes, for a word, the frequency of letters.
    As we want as much diversity as possible, we use inverse frequency and 
    put as numerator the number of times this letter is met
    '''
    # How nice is it!
    letters_count = Counter(word)
    score = 0.0
    for l in letters_count:
        if l in FREQUENCIES:
            count = letters_count[l]
            frequency = FREQUENCIES[l]
            score = score + count*frequency
    return -1*score

def play_turn(number_of_guesses,guess_length, letters, valid_words):
    '''
    At each turn, we enter the most probable word and check the result
    '''
    pass

def main():
    words = list()
    # word list courtesy of https://chrplr.github.io/openlexicon/datasets-info/Liste-de-mots-francais-Gutenberg/README-liste-francais-Gutenberg.html
    with codecs.open("liste.de.mots.francais.frgut.txt", "r", "utf-8") as f:
        words = f.readlines()
        for text in words[:]:
            words.append(unidecode.unidecode(text).upper().strip())
    initial_image_path = capture_sutom_window(0)
    rows = find_sutom_rows_in(initial_image_path)
    # So, how many rows do we have?
    number_of_guesses = len(rows)
    # What is the length of the word we want to guess ?
    rows_list = list(rows)
    guess_length = len(rows[rows_list[0]])
    # And get first letter
    letters = detect_letters_and_status_in(initial_image_path, rows[rows_list[0]])
    # At first turn, we're only interested by the first letter value (and not even the color)
    first_letter = letters[0]['text']
    # extract the words with the right number of letters
    valid_words = list(filter(lambda word: len(word)==number_of_guesses and word.startswith(first_letter), words))
    valid_words = sorted(valid_words, key=get_letters_frequency)
    turn = 0
    while turn<number_of_guesses:
        play_turn(number_of_guesses, guess_length, letters, valid_words)
        turn = turn+1

if __name__ == "__main__":
    main()