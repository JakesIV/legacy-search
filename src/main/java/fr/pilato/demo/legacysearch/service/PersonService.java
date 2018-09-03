package fr.pilato.demo.legacysearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.pilato.demo.legacysearch.dao.ElasticsearchDao;
import fr.pilato.demo.legacysearch.dao.HibernateService;
import fr.pilato.demo.legacysearch.dao.PersonDao;
import fr.pilato.demo.legacysearch.dao.SearchDao;
import fr.pilato.demo.legacysearch.domain.Person;
import fr.pilato.demo.legacysearch.helper.PersonGenerator;
import fr.pilato.demo.legacysearch.webapp.InitResult;
import org.dozer.DozerBeanMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restx.factory.Component;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PersonService {
    final Logger logger = LoggerFactory.getLogger(PersonService.class);

    private final PersonDao personDao;
    private final SearchDao searchDao;
    private final HibernateService hibernateService;
    private final ObjectMapper mapper;
    private final DozerBeanMapper dozerBeanMapper;
    private final ElasticsearchDao elasticsearchDao;

    @Inject
    public PersonService(PersonDao personDao, SearchDao searchDao,
                         HibernateService hibernateService,
                         ElasticsearchDao elasticsearchDao,
                         ObjectMapper mapper, DozerBeanMapper dozerBeanMapper) {
        this.personDao = personDao;
        this.searchDao = searchDao;
        this.hibernateService = hibernateService;
        this.mapper = mapper;
        this.dozerBeanMapper = dozerBeanMapper;
        this.elasticsearchDao = elasticsearchDao;
    }

    public Person get(Integer id) {
        hibernateService.beginTransaction();

        Person person = personDao.get(id);
        logger.debug("get({})={}", id, person);

        hibernateService.commitTransaction();
        return person;
    }

    public Person save(Person person) {
        hibernateService.beginTransaction();

        Person personDb = personDao.save(person);
        try {
            elasticsearchDao.save(personDb);
        } catch (Exception e) {
            logger.error("Houston, we have a problem!", e);
        }

        hibernateService.commitTransaction();
        return personDb;
    }

    public Person upsert(Integer id, Person person) {
        // We try to find an existing document
        Person personDb = get(id);
        if (personDb != null) {
            dozerBeanMapper.map(person, personDb);
            person = personDb;
            person.setId(id);
        }
        person = save(person);

        return person;
    }

    public boolean delete(Integer id) {
        logger.debug("Person: {}", id);

        if (id == null) {
            return false;
        }

        hibernateService.beginTransaction();
        Person person = personDao.get(id);
        if (person == null) {
            logger.debug("Person with reference {} does not exist", id);
            hibernateService.commitTransaction();
            return false;
        }
        personDao.delete(person);
        elasticsearchDao.delete(person.idAsString());
        hibernateService.commitTransaction();

        logger.debug("Person deleted: {}", id);

        return true;
    }

    public String search(String q, String f_country, String f_date, Integer from, Integer size) throws IOException {
        QueryBuilder query;
        // If the user does not provide any text to query, let's match all documents
        if (Strings.isEmpty(q)) {
            query = QueryBuilders.matchAllQuery();
        } else {
            query = QueryBuilders
                    .multiMatchQuery(q)
                        .field("fulltext")
                        .field("name", 3.0f);
        }

        SearchResponse response = elasticsearchDao.search(query, from, size);

        if (logger.isDebugEnabled()) logger.debug("search({})={} persons", q, response.getHits().getTotalHits());

        return response.toString();
    }

    public String advancedSearch(String name, String country, String city, Integer from, Integer size) throws IOException {
        QueryBuilder query;

        // If the user does not provide any text to query, let's match all documents
        if (Strings.isEmpty(name) && Strings.isEmpty(country) && Strings.isEmpty(city)) {
            query = QueryBuilders.matchAllQuery();
        } else {
            BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
            if (Strings.hasText(name)) {
                boolQueryBuilder.must(
                        QueryBuilders.matchQuery("name.autocomplete", name)
                );
            }
            if (Strings.hasText(country)) {
                boolQueryBuilder.must(
                        QueryBuilders.matchQuery("address.country.autocomplete", country)
                );
            }
            if (Strings.hasText(city)) {
                boolQueryBuilder.must(
                        QueryBuilders.matchQuery("address.city.autocomplete", city)
                );
            }

            query = boolQueryBuilder;
        }

        SearchResponse response = elasticsearchDao.search(query, from, size);

        logger.debug("advancedSearch({},{},{})={} persons", name, country, city, response.getHits().getTotalHits());

        return response.toString();
    }

    private AtomicInteger currentItem = new AtomicInteger();
    private long start = 0;

    public InitResult init(Integer size) {
        currentItem.set(0);

        logger.debug("Initializing database for {} persons", size);
        start = System.currentTimeMillis();

        try {
            hibernateService.beginTransaction();
            Person joe = PersonGenerator.personGenerator();
            joe.setName("Joe Smith");
            joe.getAddress().setCountry("France");
            joe.getAddress().setCity("Paris");
            joe.getAddress().setCountrycode("FR");
            save(joe);
            currentItem.incrementAndGet();

            Person franceGall = PersonGenerator.personGenerator();
            franceGall.setName("France Gall");
            franceGall.setGender("female");
            franceGall.getAddress().setCountry("Italy");
            franceGall.getAddress().setCity("Ischia");
            joe.getAddress().setCountrycode("IT");
            save(franceGall);
            currentItem.incrementAndGet();

            // We generate numPersons persons
            for (int i = 2; i < size; i++) {
                Person person = PersonGenerator.personGenerator();
                save(person);
                currentItem.incrementAndGet();
            }
        } catch (IOException e) {
            logger.warn("error while generating data", e);
        } finally {
            hibernateService.commitTransaction();
        }

        long took = System.currentTimeMillis() - start;

        logger.debug("Database initialized with {} persons. Took: {} ms, around {} per second.",
                size, took, 1000 * size / took);

        return new InitResult(took, 1000 * size / took, size);
    }

    public InitResult getInitCurrentAchievement() {
        int current = currentItem.get();
        long took = System.currentTimeMillis() - start;
        return new InitResult(took, 1000 * current / took, current);
    }
}
